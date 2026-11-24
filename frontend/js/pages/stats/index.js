import { api } from '../../core/api.js';
import { currentUser } from '../../core/auth.js';
import { formatDate } from '../../core/format.js';
import { loadUnits, PARTICIPANT_STATE, unitChoices } from '../../core/refdata.js';
import { currentQuery } from '../../core/router.js';
import {
    barList, dataTable, donutChart, GRADE_SERIES, MISSING_SERIES, percentText, stackedBars,
} from '../../ui/charts.js';
import { append, h, replace } from '../../ui/escape.js';
import { filterField, filterSelect } from '../../ui/filter-field.js';
import { icon } from '../../ui/icons.js';
import { multiSelect } from '../../ui/multi-select.js';
import { pageHead } from '../../ui/page-head.js';
import { createTable } from '../../ui/table.js';
import { chartCard, cleanQuery, gradeColumns, KIND_LABEL, kpiRow, passed, segmented } from './common.js';

/**
 * Trang thống kê (M6.2) cho Admin và Super Admin. Máy chủ tự giới hạn theo phạm vi và tính theo đơn vị lúc vào lớp.
 * Điểm cá nhân và điểm tập thể đơn vị xem riêng (nút chọn loại), không bao giờ gộp chung một số liệu.
 * Hai thẻ: xếp loại theo đơn vị (bảng có cả điểm trung bình), chưa kiểm tra. Thẻ "Điểm theo đơn vị" đã bỏ ngày
 * 2026-11-20 vì trùng với thẻ xếp loại.
 */
const TABS = [['grades', 'Xếp loại theo đơn vị'], ['missing', 'Chưa kiểm tra']];

export async function render(container) {
    const me = currentUser();
    const sa = me.role === 'SUPER_ADMIN';
    const [periods, units] = await Promise.all([api('/periods'), loadUnits()]);
    const q = currentQuery();
    const filters = { periodId: q.get('periodId') || '', classId: '', unitId: '', from: '', to: '' };
    let kind = q.get('kind') === 'UNIT' ? 'UNIT' : 'PERSON';
    let tab = TABS.some(([v]) => v === q.get('tab')) ? q.get('tab') : 'grades';
    let group = sa ? 'top' : 'unit';
    let missingState = 'ALL';

    const body = h('div', { class: 'stats-body', 'aria-live': 'polite' });
    const query = (extra = {}) => cleanQuery({ ...filters, kind, ...extra });

    // ------------------------------------------------------------ bộ lọc

    const periodSel = filterSelect('s-period', periods.map((p) => ({ value: String(p.id), label: p.name })), 'Tất cả đợt',
        filters.periodId);
    // Nội dung kiểm tra phụ thuộc đợt: chọn đợt trước, danh sách là các nội dung của đợt mà người xem được thấy.
    const classSel = h('select', { id: 's-class', class: 'select', disabled: true }, h('option', { value: '' }, 'Chọn đợt trước'));
    async function loadClasses() {
        filters.classId = '';
        if (!periodSel.value) {
            replace(classSel, h('option', { value: '' }, 'Chọn đợt trước'));
            classSel.disabled = true;
            return;
        }
        try {
            const res = await api('/classes', { query: { periodId: periodSel.value, size: 100 } });
            replace(classSel, h('option', { value: '' }, 'Tất cả nội dung của đợt'),
                res.items.map((c) => h('option', { value: String(c.id) }, c.name)));
            classSel.disabled = res.items.length === 0;
        } catch {
            replace(classSel, h('option', { value: '' }, 'Không tải được danh sách'));
            classSel.disabled = true;
        }
    }
    // Chọn nhiều đơn vị (mỗi đơn vị gồm đơn vị con); không chọn là toàn bộ phạm vi.
    const unitSel = multiSelect({ id: 's-unit', allLabel: sa ? 'Toàn Lữ đoàn' : 'Toàn bộ phạm vi', unit: 'đơn vị',
        options: unitChoices(units, { onlyActive: false, onlyMine: !sa }), onChange: () => apply() });
    const fromInput = h('input', { id: 's-from', class: 'input', type: 'date' });
    const toInput = h('input', { id: 's-to', class: 'input', type: 'date' });
    const filterError = h('div', { class: 'field-error', role: 'alert' });

    const apply = () => {
        filterError.textContent = '';
        if (fromInput.value && toInput.value && fromInput.value > toInput.value) {
            filterError.textContent = 'Đến ngày phải sau hoặc bằng từ ngày.';
            return;
        }
        Object.assign(filters, { periodId: periodSel.value, classId: classSel.value, unitId: unitSel.values().join(','),
            from: fromInput.value, to: toInput.value });
        draw();
    };
    for (const el of [classSel, fromInput, toInput]) el.addEventListener('change', apply);
    periodSel.addEventListener('change', async () => {
        await loadClasses();
        apply();
    });
    const reset = h('button', { type: 'button', class: 'btn btn-secondary' }, 'Bỏ lọc');
    reset.addEventListener('click', async () => {
        for (const el of [periodSel, fromInput, toInput]) el.value = '';
        unitSel.setValues([]);
        await loadClasses();
        apply();
    });

    const filterBox = h('details', { class: 'filters', open: window.matchMedia('(min-width: 600px)').matches },
        h('summary', null, icon('search', { small: true }), 'Lọc số liệu'),
        h('form', { class: 'toolbar toolbar-dense', onsubmit: (e) => { e.preventDefault(); apply(); } },
            filterField('s-period', 'Đợt kiểm tra', periodSel),
            filterField('s-class', 'Nội dung kiểm tra', classSel),
            filterField('s-unit', 'Đơn vị (gồm đơn vị con)', unitSel.element),
            filterField('s-from', 'Ngày kiểm tra từ', fromInput),
            filterField('s-to', 'Đến ngày', toInput),
            h('div', { class: 'field field-action' }, reset),
            filterError));

    const kindSwitch = segmented('Loại điểm', [['PERSON', 'Cá nhân'], ['UNIT', 'Tập thể đơn vị']], kind,
        (v) => { kind = v; draw(); });
    const tabSwitch = segmented('Chọn thống kê', TABS, tab, (v) => { tab = v; draw(); });

    // ------------------------------------------------------------ vẽ

    const unitName = (r) => h('span', null, r.name, h('span', { class: 'cell-sub' }, r.code));
    const kindNote = () => h('p', { class: 'stats-kind' },
        h('span', { class: kind === 'UNIT' ? 'badge badge-warning' : 'badge badge-success' }, KIND_LABEL[kind]),
        kind === 'UNIT'
            ? ' Điểm chấm cho cả đơn vị như một người học. Không gộp với điểm cá nhân.'
            : ' Điểm của từng người. Điểm tập thể đơn vị xem riêng ở nút "Tập thể đơn vị".');

    async function draw() {
        replace(body, h('p', { class: 'loading' }, 'Đang tải số liệu...'));
        try {
            if (tab === 'grades') await drawGrades();
            else await drawMissing();
        } catch (e) {
            replace(body, h('div', { class: 'alert alert-error', role: 'alert' }, h('p', null, e.message)));
        }
    }

    function groupSwitch() {
        return segmented('Cách gộp đơn vị', [['top', 'Đơn vị cấp 1'], ['unit', 'Chi tiết từng đơn vị']], group,
            (v) => { group = v; draw(); });
    }

    async function drawGrades() {
        const data = await api('/stats/grade-by-unit', { query: query({ group }) });
        const t = data.totals;
        const rows = data.rows.map((r) => ({ label: r.name, sub: r.code, counts: r.counts }));
        replace(body,
            kindNote(),
            kpiRow(t, kind),
            h('div', { class: 'chart-grid' },
                chartCard('Phân bố xếp loại', t.tested ? `${t.tested} đã có điểm` : 'Chưa có điểm',
                    donutChart({ series: GRADE_SERIES, counts: t, label: 'Phân bố xếp loại',
                        center: t.tested ? percentText(passed(t), t.tested) : '—', centerSub: 'đạt trở lên' })),
                chartCard('Xếp loại theo đơn vị', 'Mỗi thanh là 100% người học của đơn vị; số bên phải là tổng số',
                    groupSwitch(),
                    stackedBars({ rows, series: [...GRADE_SERIES, ...MISSING_SERIES], label: 'Xếp loại theo đơn vị',
                        emptyText: 'Không có người học nào khớp bộ lọc.' }))),
            chartCard('Bảng số liệu', 'Tỷ lệ xếp loại tính trên số đã có điểm',
                dataTable({ caption: 'Xếp loại theo đơn vị', columns: gradeColumns(unitName), rows: data.rows,
                    foot: { name: 'Tổng cộng', code: '', counts: t } })));
    }

    async function drawMissing() {
        const first = await api('/stats/not-tested', { query: query({ state: missingState, size: 1 }) });
        const unit = kind === 'UNIT';
        const table = createTable({
            caption: 'Danh sách chưa có điểm',
            emptyText: 'Không có ai chưa có điểm. Tốt lắm!',
            columns: [
                { key: 'name', label: unit ? 'Đơn vị' : 'Họ tên', render: (r) => [
                    unit ? h('span', { class: 'cell-title' }, r.fullName)
                        : h('a', { class: 'cell-link', href: `#/users/${r.userId}` }, [r.rankSymbol, r.fullName].filter(Boolean).join(' ')),
                    unit || !r.personalCode ? null : h('span', { class: 'cell-sub mono' }, r.personalCode)] },
                unit ? null : { key: 'unit', label: 'Đơn vị lúc tham gia', render: (r) => r.unitName },
                { key: 'class', label: 'Nội dung kiểm tra', render: (r) => [h('a', { class: 'cell-link', href: `#/classes/${r.classId}` }, r.className),
                    h('span', { class: 'cell-sub' }, r.periodName)] },
                { key: 'examDate', label: 'Ngày kiểm tra', priority: 3, render: (r) => formatDate(r.examDate) || '—' },
                { key: 'state', label: 'Tình trạng', render: (r) => h('span', { class: PARTICIPANT_STATE[r.state]?.badge || 'badge' },
                    PARTICIPANT_STATE[r.state]?.label || r.state) },
                { key: 'reason', label: 'Lý do', render: (r) => (r.reasonName
                    ? [r.reasonName, r.note ? h('span', { class: 'cell-sub' }, r.note) : null]
                    : h('span', { class: 'text-muted' }, 'Chưa ghi')) },
            ].filter(Boolean),
            load: ({ page, size }) => api('/stats/not-tested', { query: query({ state: missingState, page, size }) }),
        });
        const reasons = first.reasons.map((r) => ({
            label: r.reason || 'Không lý do', value: r.count, text: String(r.count),
        }));
        const maxReason = Math.max(1, ...reasons.map((r) => r.value));
        const stateSwitch = segmented('Lọc tình trạng',
            [['ALL', `Tất cả (${first.absent + first.notTested})`], ['ABSENT', `Không kiểm tra (${first.absent})`],
                ['NOT_TESTED', `Chưa kiểm tra (${first.notTested})`]],
            missingState, (v) => { missingState = v; table.reload(true); });
        replace(body,
            kindNote(),
            h('div', { class: 'chart-grid chart-grid-side' },
                chartCard('Theo lý do', `${first.absent + first.notTested} lượt chưa có điểm`,
                    barList({ items: reasons, max: maxReason, label: 'Số lượt chưa có điểm theo lý do', tone: 'absent',
                        emptyText: 'Không có ai chưa có điểm.' })),
                chartCard('Danh sách', unit ? null : 'Chỉ hiện người hiện đang thuộc phạm vi bạn quản lý',
                    stateSwitch, table.element)));
        table.reload(true);
    }

    append(container, [
        pageHead('Thống kê',
            sa ? 'Toàn Lữ đoàn, theo đơn vị lúc tham gia.'
                : `Trong phạm vi ${me.unit?.name || 'bạn quản lý'}, theo đơn vị lúc tham gia.`),
        filterBox,
        h('div', { class: 'stats-switches' }, tabSwitch, kindSwitch),
        body]);
    if (filters.periodId) await loadClasses();
    await draw();
}
