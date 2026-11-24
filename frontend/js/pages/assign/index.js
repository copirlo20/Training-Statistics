import { api } from '../../core/api.js';
import { formatDate } from '../../core/format.js';
import { CATEGORY, categoryOptions, loadCatalogs } from '../../core/refdata.js';
import { currentQuery } from '../../core/router.js';
import { append, h, replace } from '../../ui/escape.js';
import { filterField } from '../../ui/filter-field.js';
import { icon } from '../../ui/icons.js';
import { confirmDialog, openModal } from '../../ui/modal.js';
import { multiSelect } from '../../ui/multi-select.js';
import { pageHead } from '../../ui/page-head.js';
import { toast } from '../../ui/toast.js';

/**
 * Bảng phân công (ma trận người x nội dung, ARCHITECTURE 3.12): xem từng đơn vị một (chọn ở bộ lọc; đơn vị nhỏ nhất, không gồm đơn vị con),
 * hàng là người của đơn vị đó (chỉ họ tên); cột là các nội dung kiểm tra của một đợt; ô được chọn là người vào nội dung
 * đó. Thay đổi đã chọn giữ khi chuyển trang, đơn vị, áp dụng một lần. Làm hàng loạt bằng Excel ở #/assign/import.
 *
 * Vừa khít màn hình (yêu cầu ngày 2026-11-22), không có khung cuộn riêng: số người mỗi trang tính theo chiều cao còn
 * trống (cả bảng và thanh Trước, Sau nằm gọn trong màn hình; màn hình thấp quá thì vừa một màn hình khi cuộn tới
 * bảng), số cột nội dung tính theo bề ngang (nhiều hơn thì chia nhóm cột, chuyển ở ô góc). Tính lại khi đổi cỡ cửa sổ.
 * Trước, Sau dưới bảng chuyển trang; hết trang của đơn vị thì sang đơn vị kế.
 *
 * Máy chủ quyết định quyền: Super Admin và giám khảo xem toàn bộ danh sách và thêm, gỡ thẳng vào nội dung của mình;
 * Admin chỉ thấy người thuộc đơn vị mình quản lý và gửi đề nghị. Giao diện chỉ phản chiếu quyền đó (cột có chế độ).
 */
/** Số người mỗi lần lấy từ máy chủ (tối đa của API); chia trang hiển thị làm ở trình duyệt. */
const CHUNK = 200;
const MIN_ROWS = 5;
const MODE = {
    DIRECT: { label: 'Thêm ngay', badge: 'badge badge-success' },
    REQUEST: { label: 'Gửi đề nghị', badge: 'badge badge-warning' },
    VIEW: { label: 'Chỉ xem', badge: 'badge' },
};
const STATE_TEXT = {
    IN: 'đã tham gia',
    IN_GRADED: 'đã có điểm, chỉ gỡ được ở trang nội dung kiểm tra',
    PENDING_ADD: 'đang chờ duyệt thêm',
    PENDING_REMOVE: 'đang chờ duyệt gỡ',
};

export async function render(container) {
    const [periods, cat] = await Promise.all([api('/periods'), loadCatalogs()]);
    const excelLink = h('a', { class: 'btn', href: '#/assign/import' }, icon('log', { small: true }), h('span', null, 'Phân công bằng Excel'));
    const head = pageHead('Phân công người học',
        'Bấm vào ô để chọn từng người, bấm số ở dòng đơn vị để chọn cả đơn vị, rồi bấm Áp dụng. '
            + 'Super Admin và giám khảo thêm, gỡ ngay; Admin đơn vị gửi đề nghị để giám khảo hoặc Super Admin duyệt.',
        [excelLink, h('a', { class: 'btn btn-secondary', href: '#/requests' }, 'Danh sách đề nghị')]);
    if (periods.length === 0) {
        append(container, [head, h('div', { class: 'alert alert-info' }, h('p', null, 'Chưa có đợt kiểm tra nào.'))]);
        return;
    }

    const q = currentQuery();
    const firstOpen = periods.find((p) => p.status === 'OPEN') || periods[0];
    const filters = { periodId: q.get('periodId') || String(firstOpen.id), unit: q.get('unit') || '', specialtyIds: [],
        categories: [], q: '', includeClosed: false, page: 0 };
    /** Mọi đơn vị có người trong phạm vi (allUnits) và các đơn vị đang duyệt qua bằng Trước, Sau (unitList): các đơn vị
     *  đã chọn ở bộ lọc, không chọn gì là tất cả. */
    let allUnits = [];
    let unitList = [];
    /** Thay đổi đã chọn, giữ khi đổi bộ lọc, trang: khóa "classId:userId". */
    const staged = new Map();
    let data = null;
    let loadSeq = 0;
    let cellRefresh = new Map();
    /** Trang hiển thị trong lần lấy hiện tại: [chỉ số dòng đầu, chỉ số sau dòng cuối]. */
    let pages = [[0, 0]];
    let localPage = 0;
    /** Nhóm cột nội dung đang xem và số cột vừa bề ngang. */
    let colStart = 0;
    let colFit = Infinity;
    let table = null;

    // ------------------------------------------------------------ bộ lọc
    const periodSel = h('select', { id: 'as-period', class: 'select' }, periods.map((p) => h('option', { value: String(p.id) },
        p.name + (p.status === 'OPEN' ? '' : ' (đã đóng)'))));
    periodSel.value = filters.periodId;
    const apply = () => {
        Object.assign(filters, { specialtyIds: specialtySel.values(), categories: categorySel.values(), q: search.value.trim(), page: 0 });
        load();
    };
    // Chỉ đơn vị có người (thuộc đúng đơn vị đó) trong phạm vi: Super Admin, giám khảo toàn Lữ đoàn; Admin nhánh mình.
    const unitSel = multiSelect({ id: 'as-unit', allLabel: 'Tất cả đơn vị', unit: 'đơn vị', onChange: () => pickUnits() });
    const categorySel = multiSelect({ id: 'as-category', allLabel: 'Mọi đối tượng', unit: 'đối tượng', searchable: false,
        options: categoryOptions(), onChange: apply });
    const specialtySel = multiSelect({ id: 'as-specialty', allLabel: 'Tất cả chuyên ngành', unit: 'chuyên ngành', onChange: apply,
        options: cat.specialties.filter((s) => s.active).map((s) => ({ value: String(s.id), label: s.name })) });
    const search = h('input', { id: 'as-q', class: 'input', type: 'search', autocomplete: 'off',
        placeholder: 'Họ tên hoặc mã định danh' });
    // Nút chọn một trong hai (kiểu radio): chỉ nội dung đang mở, hoặc cả nội dung đã đóng (chỉ xem).
    const scopeSeg = h('div', { class: 'segmented', role: 'radiogroup', 'aria-label': 'Nội dung hiển thị' });
    const drawScope = () => replace(scopeSeg, [[false, 'Đang mở'], [true, 'Tất cả']].map(([value, label]) => h('button', {
        type: 'button', class: 'btn btn-secondary', role: 'radio', 'aria-checked': String(filters.includeClosed === value),
        'aria-pressed': String(filters.includeClosed === value),
        onclick: () => {
            if (filters.includeClosed === value) return;
            filters.includeClosed = value;
            colStart = 0;
            drawScope();
            apply();
        },
    }, label)));
    drawScope();

    const unitIndex = () => unitList.findIndex((u) => String(u.id) === filters.unit);
    const currentUnit = () => unitList[unitIndex()] || null;

    function selectUnit(id, { toEnd = false } = {}) {
        filters.unit = String(id);
        filters.page = 0;
        load({ toEnd });
    }

    function refreshUnitList() {
        const chosen = new Set(unitSel.values());
        unitList = chosen.size ? allUnits.filter((u) => chosen.has(String(u.id))) : allUnits;
    }

    // Đổi tập đơn vị đã chọn: đơn vị đang xem vẫn trong tập thì giữ, không thì sang đơn vị đầu của tập.
    function pickUnits() {
        refreshUnitList();
        if (unitList.some((u) => String(u.id) === filters.unit)) drawNav();
        else if (unitList.length) selectUnit(unitList[0].id);
    }

    async function loadUnits() {
        excelLink.href = `#/assign/import?periodId=${filters.periodId}`;
        try {
            const units = await api('/assignments/units', { query: { periodId: filters.periodId } });
            allUnits = units.filter((u) => u.people > 0);
        } catch {
            allUnits = [];
        }
        unitSel.setOptions(allUnits.map((u) => ({ value: String(u.id), label: u.name, sub: u.parentName || null })));
        refreshUnitList();
        if (!unitList.some((u) => String(u.id) === filters.unit)) filters.unit = unitList.length ? String(unitList[0].id) : '';
    }

    let timer;
    search.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(apply, 300); });
    periodSel.addEventListener('change', async () => {
        if (staged.size > 0 && !(await confirmDialog(`Bỏ ${staged.size} thay đổi chưa áp dụng và chuyển đợt?`,
            { title: 'Chuyển đợt', okLabel: 'Bỏ và chuyển' }))) {
            periodSel.value = filters.periodId;
            return;
        }
        staged.clear();
        drawBar();
        Object.assign(filters, { periodId: periodSel.value, specialtyIds: [], categories: [], q: '', page: 0 });
        colStart = 0;
        categorySel.setValues([]);
        specialtySel.setValues([]);
        search.value = '';
        loadUnits().then(() => load());
    });

    function resetFilters() {
        unitSel.setValues([]);
        refreshUnitList();
        categorySel.setValues([]);
        specialtySel.setValues([]);
        search.value = '';
        filters.includeClosed = false;
        colStart = 0;
        drawScope();
        apply();
    }
    const reset = h('button', { type: 'button', class: 'btn btn-secondary', onclick: resetFilters }, 'Bỏ lọc');
    const filterBox = h('details', { class: 'filters', open: window.matchMedia('(min-width: 600px)').matches },
        h('summary', null, icon('search', { small: true }), 'Lọc người và nội dung'),
        h('form', { class: 'toolbar toolbar-dense', onsubmit: (e) => { e.preventDefault(); clearTimeout(timer); apply(); } },
            filterField('as-period', 'Đợt kiểm tra', periodSel),
            filterField('as-unit', 'Đơn vị', unitSel.element),
            filterField('as-category', 'Đối tượng (chọn nhiều)', categorySel.element),
            filterField('as-specialty', 'Chuyên ngành (chọn nhiều)', specialtySel.element),
            filterField('as-q', 'Tìm tên hoặc mã', search),
            h('div', { class: 'field field-action' }, h('span', { class: 'field-caption' }, 'Nội dung'), scopeSeg),
            h('div', { class: 'field field-action' }, reset)));

    // ------------------------------------------------------------ bảng
    const viewerNote = h('div', { class: 'assign-note', 'aria-live': 'polite' });
    const gridHost = h('div', { class: 'assign-host', 'aria-busy': 'true' }, h('p', { class: 'loading' }, 'Đang tải bảng...'));
    const legend = h('ul', { class: 'assign-legend', 'aria-label': 'Chú giải' },
        legendItem('is-in', 'check', 'Đã tham gia'), legendItem('is-add', 'plus', 'Sẽ thêm'), legendItem('is-remove', 'minus', 'Sẽ gỡ'),
        legendItem('is-pending', 'clock', 'Chờ duyệt'), legendItem('is-graded', 'lock', 'Đã có điểm'),
        h('li', null, h('span', { class: 'assign-group-box', 'aria-hidden': 'true' }, '2/4'), 'Số ở dòng đơn vị: bấm để chọn cả đơn vị'));

    function legendItem(cls, name, text) {
        return h('li', null, h('span', { class: `assign-box ${cls}`, 'aria-hidden': 'true' }, icon(name, { small: true })), text);
    }

    // Trước, Sau dưới bảng: chuyển trang, hết trang thì sang đơn vị kế.
    const prevText = h('span');
    const nextText = h('span');
    const prevBtn = h('button', { type: 'button', class: 'btn btn-secondary' }, icon('chevron-left', { small: true }), prevText);
    const nextBtn = h('button', { type: 'button', class: 'btn btn-secondary' }, nextText, icon('chevron-right', { small: true }));
    const nav = h('div', { class: 'assign-nav', role: 'group', 'aria-label': 'Chuyển trang, đơn vị' }, h('div', { class: 'btn-row' }, prevBtn, nextBtn));
    const frame = h('div', { class: 'assign-frame' }, gridHost, nav);

    const keyOf = (colId, userId) => `${colId}:${userId}`;

    /** Hành động bấm vào ô sẽ làm: ADD, REMOVE, hoặc null nếu ô không bấm được. */
    function clickAction(col, row) {
        if (col.mode === 'VIEW') return null;
        const state = row.cells[col.id];
        if (!state) return 'ADD';
        return state === 'IN' ? 'REMOVE' : null;
    }

    function paint(btn, col, row) {
        const state = row.cells[col.id] || null;
        const s = staged.get(keyOf(col.id, row.userId));
        const act = clickAction(col, row);
        let cls = 'is-empty';
        let name = null;
        let text = 'chưa tham gia';
        if (s) {
            cls = s.action === 'ADD' ? 'is-add' : 'is-remove';
            name = s.action === 'ADD' ? 'plus' : 'minus';
            text = s.action === 'ADD' ? 'sẽ thêm' : 'sẽ gỡ';
        } else if (state === 'IN') {
            cls = 'is-in';
            name = 'check';
            text = STATE_TEXT.IN;
        } else if (state === 'IN_GRADED') {
            cls = 'is-graded';
            name = 'lock';
            text = STATE_TEXT.IN_GRADED;
        } else if (state) {
            cls = 'is-pending';
            name = 'clock';
            text = STATE_TEXT[state];
        }
        const todo = s ? 'bấm để bỏ chọn' : act === 'ADD' ? 'bấm để chọn thêm' : act === 'REMOVE' ? 'bấm để chọn gỡ' : null;
        btn.className = `assign-cell ${cls}`;
        btn.setAttribute('aria-label', `${row.fullName}, ${col.name}: ${text}${todo ? `. ${todo}` : ''}`);
        btn.title = `${text}${todo ? ` (${todo})` : ''}`;
        btn.setAttribute('aria-disabled', String(!s && !act));
        btn.setAttribute('aria-pressed', String(Boolean(s)));
        replace(btn, h('span', { class: 'assign-box', 'aria-hidden': 'true' }, name ? icon(name, { small: true }) : null));
    }

    function toggle(col, row) {
        const key = keyOf(col.id, row.userId);
        if (staged.has(key)) {
            staged.delete(key);
        } else {
            const action = clickAction(col, row);
            if (!action) return;
            staged.set(key, { classId: col.id, userId: row.userId, action, who: row.fullName, what: col.name, mode: col.mode });
        }
        cellRefresh.get(key)?.();
        refreshGroups();
        drawBar();
    }

    function makeCell(col, row) {
        const btn = h('button', { type: 'button', class: 'assign-cell' });
        btn.addEventListener('click', () => toggle(col, row));
        const update = () => paint(btn, col, row);
        cellRefresh.set(keyOf(col.id, row.userId), update);
        update();
        return btn;
    }

    /** Chọn hoặc bỏ chọn cả một nhóm đơn vị trong một cột: chỉ thêm người chưa có, không gỡ hàng loạt. */
    function bulk(col, rows) {
        const todo = rows.filter((r) => clickAction(col, r) === 'ADD');
        if (todo.length === 0) return;
        const allStaged = todo.every((r) => staged.has(keyOf(col.id, r.userId)));
        for (const r of todo) {
            const key = keyOf(col.id, r.userId);
            if (allStaged) staged.delete(key);
            else if (!staged.has(key)) staged.set(key, { classId: col.id, userId: r.userId, action: 'ADD', who: r.fullName, what: col.name, mode: col.mode });
            cellRefresh.get(key)?.();
        }
        refreshGroups();
        drawBar();
    }

    let groupRefresh = [];
    const refreshGroups = () => groupRefresh.forEach((f) => f());

    function groupCell(col, rows) {
        const total = rows.length;
        const btn = h('button', { type: 'button', class: 'assign-group-btn' });
        const update = () => {
            const have = rows.filter((r) => r.cells[col.id] && r.cells[col.id] !== 'PENDING_REMOVE').length
                + rows.filter((r) => staged.get(keyOf(col.id, r.userId))?.action === 'ADD').length;
            const addable = rows.some((r) => clickAction(col, r) === 'ADD');
            replace(btn, h('span', { class: 'assign-group-box', 'aria-hidden': 'true' }, `${Math.min(have, total)}/${total}`));
            btn.disabled = !addable;
            btn.setAttribute('aria-label', `${rows[0].unitName}, ${col.name}: ${Math.min(have, total)} trên ${total} người`
                + (addable ? '. Bấm để chọn hoặc bỏ chọn cả nhóm' : ''));
            btn.title = addable ? 'Chọn hoặc bỏ chọn cả nhóm đơn vị' : '';
        };
        btn.addEventListener('click', () => bulk(col, rows));
        groupRefresh.push(update);
        update();
        return btn;
    }

    // ------------------------------------------------------------ không có kết quả
    function emptyState(iconName, title, lines, actions = []) {
        table = null;
        replace(gridHost, h('div', { class: 'assign-empty', role: 'status' },
            h('span', { class: 'assign-empty-icon', 'aria-hidden': 'true' }, icon(iconName)),
            h('h2', null, title),
            lines,
            actions.length ? h('div', { class: 'btn-row' }, actions) : null));
    }

    /** Các điều kiện lọc đang bật, dạng chữ. */
    function activeFilters() {
        const out = [];
        if (filters.categories.length) {
            out.push(`Đối tượng: ${filters.categories.map((c) => CATEGORY[c]?.short || c).join(', ')}`);
        }
        if (filters.specialtyIds.length) {
            const names = filters.specialtyIds.map((id) => cat.specialties.find((s) => String(s.id) === id)?.name).filter(Boolean);
            out.push(`Chuyên ngành: ${names.join(', ')}`);
        }
        if (filters.q) out.push(`Từ khóa: “${filters.q}”`);
        return out;
    }

    function noMatch() {
        const unit = currentUnit();
        const conds = activeFilters();
        const i = unitIndex();
        const actions = [];
        if (conds.length) actions.push(h('button', { type: 'button', class: 'btn', onclick: resetFilters }, 'Bỏ lọc'));
        if (i >= 0 && i < unitList.length - 1) {
            actions.push(h('button', { type: 'button', class: 'btn btn-secondary', onclick: () => selectUnit(unitList[i + 1].id) },
                h('span', null, 'Xem đơn vị sau'), icon('chevron-right', { small: true })));
        }
        emptyState('search', 'Không tìm thấy người phù hợp', [
            h('p', null, conds.length
                ? `${unit ? unit.name : 'Đơn vị này'} không có người nào khớp với điều kiện lọc:`
                : `${unit ? unit.name : 'Đơn vị này'} hiện không có người nào trong phạm vi của bạn.`),
            conds.length ? h('ul', { class: 'assign-empty-filters' }, conds.map((c) => h('li', { class: 'badge' }, c))) : null,
            h('p', { class: 'text-muted' }, conds.length
                ? 'Hãy bỏ bớt điều kiện lọc, hoặc chuyển sang đơn vị khác.'
                : 'Hãy chọn đơn vị khác ở bộ lọc hoặc bằng nút bên dưới.'),
        ], actions);
    }

    // ------------------------------------------------------------ vẽ bảng
    function drawGrid() {
        cellRefresh = new Map();
        groupRefresh = [];
        const all = data.columns;
        if (all.length === 0) {
            emptyState('class', 'Chưa có nội dung kiểm tra để phân công', [
                h('p', null, filters.includeClosed
                    ? 'Đợt này chưa có nội dung kiểm tra nào bạn được xem.'
                    : 'Đợt này chưa có nội dung kiểm tra đang mở nào bạn thao tác được.'),
                h('p', { class: 'text-muted' }, 'Nội dung đã đóng chỉ hiện khi chọn "Tất cả" ở mục Nội dung của bộ lọc.'),
            ], filters.includeClosed ? [] : [h('button', { type: 'button', class: 'btn btn-secondary', onclick: () => {
                filters.includeClosed = true;
                drawScope();
                apply();
            } }, 'Xem cả nội dung đã đóng')]);
            return;
        }
        if (data.rows.length === 0) {
            noMatch();
            return;
        }
        const count = Math.min(all.length, colFit);
        colStart = Math.max(0, Math.min(colStart, all.length - count));
        const cols = all.slice(colStart, colStart + count);

        // Tiêu đề cột chỉ tên và ngày; chế độ (thêm ngay, gửi đề nghị, chỉ xem) đọc được bằng trình đọc màn hình
        // và hiện lại ở bước xác nhận trước khi áp dụng.
        const colHead = (c) => h('th', { scope: 'col', class: 'assign-col', title: `${c.name}: ${MODE[c.mode].label}` },
            h('span', { class: 'assign-col-name' }, c.name),
            c.examDate ? h('span', { class: 'cell-sub' }, formatDate(c.examDate)) : null,
            h('span', { class: 'visually-hidden' }, `, ${MODE[c.mode].label}`));

        let colNav = null;
        if (cols.length < all.length) {
            const step = (dir) => {
                colStart = Math.max(0, Math.min(all.length - count, colStart + dir * count));
                drawGrid();
                paginate('keep');
            };
            colNav = h('div', { class: 'assign-colnav' },
                h('span', null, `Nội dung ${colStart + 1}–${colStart + cols.length}/${all.length}`),
                h('div', { class: 'btn-row' },
                    h('button', { type: 'button', class: 'btn btn-secondary btn-icon', 'aria-label': 'Nhóm nội dung trước',
                        disabled: colStart === 0, onclick: () => step(-1) }, icon('chevron-left', { small: true })),
                    h('button', { type: 'button', class: 'btn btn-secondary btn-icon', 'aria-label': 'Nhóm nội dung sau',
                        disabled: colStart + count >= all.length, onclick: () => step(1) }, icon('chevron-right', { small: true }))));
        }

        // Mỗi lần xem một đơn vị: một dòng đơn vị (chọn cả đơn vị) rồi các dòng người, chỉ họ tên.
        const first = data.rows[0];
        const body = [h('tr', { class: 'assign-group' },
            h('th', { scope: 'rowgroup', class: 'assign-name' },
                h('span', { class: 'assign-unit' }, first.unitName),
                first.parentUnitName ? h('span', { class: 'cell-sub' }, first.parentUnitName) : null),
            cols.map((c) => h('td', null, c.mode === 'VIEW' ? null : groupCell(c, data.rows))))];
        for (const r of data.rows) {
            const more = [r.rankSymbol, r.category, r.specialties.join(', ')].filter(Boolean).join(' · ');
            body.push(h('tr', { class: 'assign-row' },
                h('th', { scope: 'row', class: 'assign-name', title: more || null },
                    h('span', { class: 'assign-person' }, r.fullName)),
                cols.map((c) => h('td', null, c.mode === 'VIEW' && !r.cells[c.id] ? null : makeCell(c, r)))));
        }

        table = h('table', { class: 'assign' },
            h('caption', { class: 'visually-hidden' }, `Bảng phân công người học, đợt ${data.period.name}`),
            h('thead', null, h('tr', null, h('th', { scope: 'col', class: 'assign-corner' }, colNav), cols.map(colHead))),
            h('tbody', null, body));
        replace(gridHost, h('div', { class: 'assign-table' }, table));
    }

    // ------------------------------------------------------------ vừa khít màn hình
    const height = (el) => (el ? el.getBoundingClientRect().height : 0);
    const shown = (el) => el && getComputedStyle(el).display !== 'none';

    /** Số cột nội dung vừa bề ngang vùng nội dung (cột người cố định). */
    function measureColFit() {
        const corner = table.querySelector('.assign-corner');
        const col = table.querySelector('th.assign-col');
        if (!corner || !col) return Infinity;
        const avail = container.getBoundingClientRect().width - 2;
        return Math.max(1, Math.floor((avail - corner.getBoundingClientRect().width) / col.getBoundingClientRect().width));
    }

    /** Thanh áp dụng hiện khi có thay đổi: luôn chừa chỗ để bảng không phải chia lại khi thanh hiện ra. */
    function barSpace() {
        const wasHidden = bar.hidden;
        if (wasHidden) {
            bar.style.visibility = 'hidden';
            bar.hidden = false;
        }
        const space = bar.getBoundingClientRect().height + parseFloat(getComputedStyle(bar).marginTop || '0');
        if (wasHidden) {
            bar.hidden = true;
            bar.style.visibility = '';
        }
        return space;
    }

    /** Chiều cao dành cho các dòng người. */
    function rowBudget(rowH) {
        const fixed = height(table.tHead) + height(table.querySelector('tr.assign-group')) + 2
            + height(nav) + parseFloat(getComputedStyle(nav).marginTop || '0') + barSpace();
        const main = document.getElementById('main');
        const footer = document.querySelector('.app-footer');
        const below = (main ? parseFloat(getComputedStyle(main).paddingBottom) || 0 : 0) + (shown(footer) ? height(footer) : 0);
        // Cả trang không phải cuộn: bảng bắt đầu ở vị trí hiện tại.
        const top = frame.getBoundingClientRect().top + window.scrollY;
        const noScroll = window.innerHeight - top - fixed - below;
        if (noScroll >= MIN_ROWS * rowH) return noScroll;
        // Màn hình thấp: cuộn tới bảng một lần thì cả bảng nằm gọn trong màn hình.
        const header = document.getElementById('app-header');
        const tabbar = document.querySelector('.tabbar');
        const gap = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--sp-4')) * 16 || 16;
        return Math.max(MIN_ROWS * rowH, window.innerHeight - height(header) - (shown(tabbar) ? height(tabbar) : 0) - fixed - gap);
    }

    /** Chia các dòng người thành trang vừa màn hình. position: first, last, hoặc keep (giữ dòng đầu đang xem). */
    function paginate(position = 'keep') {
        if (!table) {
            pages = [[0, 0]];
            localPage = 0;
            drawNav();
            return;
        }
        const keepRow = pages[localPage]?.[0] ?? 0;
        const rows = [...table.tBodies[0].querySelectorAll('tr.assign-row')];
        rows.forEach((r) => { r.hidden = false; });
        const heights = rows.map((r) => r.getBoundingClientRect().height);
        const budget = rowBudget(heights[0] || 44);
        pages = [];
        let start = 0;
        let used = 0;
        heights.forEach((hgt, i) => {
            if (i > start && used + hgt > budget) {
                pages.push([start, i]);
                start = i;
                used = 0;
            }
            used += hgt;
        });
        pages.push([start, rows.length]);
        if (position === 'first') localPage = 0;
        else if (position === 'last') localPage = pages.length - 1;
        else localPage = Math.max(0, pages.findIndex(([s, e]) => keepRow >= s && keepRow < e));
        showPage();
    }

    function showPage() {
        const [s, e] = pages[localPage] || [0, 0];
        table?.tBodies[0].querySelectorAll('tr.assign-row').forEach((r, i) => { r.hidden = i < s || i >= e; });
        drawNav();
    }

    /** Đo lại cột rồi dòng (sau khi tải, đổi cỡ cửa sổ, mở hay đóng bộ lọc). */
    function layout(position = 'keep') {
        if (table) {
            const fit = measureColFit();
            const before = Math.min(data.columns.length, colFit);
            colFit = fit;
            if (Math.min(data.columns.length, colFit) !== before) {
                colStart = Math.floor(colStart / Math.min(data.columns.length, colFit)) * Math.min(data.columns.length, colFit);
                drawGrid();
            }
        }
        paginate(position);
    }

    const hasPrevPage = () => localPage > 0 || filters.page > 0;
    const hasNextPage = () => localPage < pages.length - 1 || Boolean(data && (filters.page + 1) * CHUNK < data.total);

    function drawNav() {
        const i = unitIndex();
        const unit = currentUnit();
        const prevPage = hasPrevPage();
        const nextPage = hasNextPage();
        replace(prevText, prevPage ? 'Trước' : 'Đơn vị trước');
        replace(nextText, nextPage ? 'Sau' : 'Đơn vị sau');
        prevBtn.setAttribute('aria-label', prevPage ? 'Trang trước' : 'Đơn vị trước');
        nextBtn.setAttribute('aria-label', nextPage ? 'Trang sau' : 'Đơn vị sau');
        prevBtn.disabled = !prevPage && i <= 0;
        nextBtn.disabled = !nextPage && (i < 0 || i >= unitList.length - 1);
        const parts = [];
        if (unit) parts.push(unit.name);
        if (table && data?.total) {
            const [s, e] = pages[localPage];
            const base = filters.page * CHUNK;
            parts.push(`Người ${base + s + 1}–${base + e}/${data.total}`);
        }
        if (i >= 0) parts.push(`Đơn vị ${i + 1}/${unitList.length}`);
        nav.hidden = unitList.length === 0;
        nav.dataset.unitId = filters.unit;
        // Vị trí đang xem không in thành chữ (giữ thanh gọn dưới cột cuối), nhưng trình đọc màn hình đọc được.
        nav.setAttribute('aria-label', parts.length ? `Chuyển trang, đơn vị (${parts.join(', ')})` : 'Chuyển trang, đơn vị');
    }

    prevBtn.addEventListener('click', () => {
        if (localPage > 0) {
            localPage--;
            showPage();
        } else if (filters.page > 0) {
            filters.page--;
            load({ lastPage: true });
        } else {
            const i = unitIndex();
            if (i > 0) selectUnit(unitList[i - 1].id, { toEnd: true });
        }
    });
    nextBtn.addEventListener('click', () => {
        if (localPage < pages.length - 1) {
            localPage++;
            showPage();
        } else if (data && (filters.page + 1) * CHUNK < data.total) {
            filters.page++;
            load();
        } else {
            const i = unitIndex();
            if (i >= 0 && i < unitList.length - 1) selectUnit(unitList[i + 1].id);
        }
    });

    let resizeTimer;
    const onResize = () => {
        if (!container.isConnected) {
            window.removeEventListener('resize', onResize);
            return;
        }
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(() => layout('keep'), 150);
    };
    window.addEventListener('resize', onResize);
    filterBox.addEventListener('toggle', () => layout('keep'));

    // ------------------------------------------------------------ tải dữ liệu
    async function fetchChunk() {
        const query = { periodId: filters.periodId, unit: filters.unit, page: filters.page, size: CHUNK };
        if (filters.categories.length) query.category = filters.categories.join(',');
        if (filters.specialtyIds.length) query.specialtyId = filters.specialtyIds.join(',');
        if (filters.q) query.q = filters.q;
        if (filters.includeClosed) query.includeClosed = 'true';
        return api('/assignments/matrix', { query });
    }

    /**
     * @param toEnd    về trang cuối của đơn vị (bấm Trước từ đầu đơn vị sau)
     * @param lastPage về trang hiển thị cuối của lần lấy này
     */
    async function load({ toEnd = false, lastPage = false } = {}) {
        const seq = ++loadSeq;
        if (!filters.unit) {
            data = null;
            emptyState('unit', 'Chưa có người để phân công', [
                h('p', null, 'Không có đơn vị nào có người trong phạm vi của bạn ở đợt này.'),
                h('p', { class: 'text-muted' }, 'Người dùng cần thuộc một đơn vị và đang hoạt động thì mới hiện ở bảng phân công.'),
            ]);
            replace(viewerNote, null);
            paginate('first');
            return;
        }
        gridHost.setAttribute('aria-busy', 'true');
        try {
            let result = await fetchChunk();
            if (toEnd && result.total > CHUNK) {
                filters.page = Math.ceil(result.total / CHUNK) - 1;
                result = await fetchChunk();
            }
            if (seq !== loadSeq) return;
            data = result;
            replace(viewerNote, data.viewer.fullList
                ? 'Bạn xem được toàn bộ danh sách người của Lữ đoàn.'
                : `Bạn chỉ xem được người thuộc ${data.viewer.scopeUnitName} và các đơn vị cấp dưới.`);
            drawGrid();
            layout(toEnd || lastPage ? 'last' : 'first');
        } catch (e) {
            if (seq !== loadSeq) return;
            data = null;
            table = null;
            replace(gridHost, h('div', { class: 'alert alert-error', role: 'alert' }, h('p', null, e.message)));
            replace(viewerNote, null);
            paginate('first');
        } finally {
            if (seq === loadSeq) gridHost.removeAttribute('aria-busy');
        }
    }

    // ------------------------------------------------------------ thanh áp dụng
    const barText = h('span', { class: 'assign-bar-text', 'aria-live': 'polite' });
    const listBtn = h('button', { type: 'button', class: 'btn btn-secondary' }, 'Xem');
    const clearBtn = h('button', { type: 'button', class: 'btn btn-secondary' }, 'Bỏ chọn');
    const applyBtn = h('button', { type: 'button', class: 'btn' });
    const bar = h('div', { class: 'assign-bar', hidden: true, role: 'region', 'aria-label': 'Thay đổi đang chọn' },
        barText, h('div', { class: 'btn-row' }, listBtn, clearBtn, applyBtn));

    const counts = () => {
        const all = [...staged.values()];
        const direct = all.filter((s) => s.mode === 'DIRECT').length;
        return { all, direct, request: all.length - direct, add: all.filter((s) => s.action === 'ADD').length };
    };

    function drawBar() {
        const c = counts();
        bar.hidden = c.all.length === 0;
        replace(barText, `${c.all.length} thay đổi: ${c.add} thêm, ${c.all.length - c.add} gỡ`
            + (c.request ? ` (${c.request} gửi đề nghị)` : ''));
        replace(applyBtn, c.request === 0 ? 'Áp dụng' : c.direct === 0 ? 'Gửi đề nghị' : 'Áp dụng và gửi đề nghị');
    }

    clearBtn.addEventListener('click', () => {
        const keys = [...staged.keys()];
        staged.clear();
        keys.forEach((k) => cellRefresh.get(k)?.());
        refreshGroups();
        drawBar();
    });

    /** Gom theo nội dung để người dùng soát lại trước khi gửi. */
    function summaryList(onRemove) {
        const byClass = new Map();
        for (const s of staged.values()) {
            if (!byClass.has(s.classId)) byClass.set(s.classId, []);
            byClass.get(s.classId).push(s);
        }
        return h('div', { class: 'assign-summary' }, [...byClass.values()].map((items) => h('section', null,
            h('h3', null, items[0].what, ' ', h('span', { class: MODE[items[0].mode].badge }, MODE[items[0].mode].label)),
            h('ul', null, items.map((s) => h('li', null,
                h('span', { class: s.action === 'ADD' ? 'assign-plus' : 'assign-minus' }, s.action === 'ADD' ? 'Thêm ' : 'Gỡ '), s.who,
                onRemove ? h('button', { type: 'button', class: 'btn btn-icon', 'aria-label': `Bỏ chọn ${s.who}`,
                    onclick: () => onRemove(s) }, icon('close', { small: true })) : null))))));
    }

    listBtn.addEventListener('click', () => {
        const body = h('div');
        const draw = () => replace(body, staged.size === 0 ? h('p', { class: 'text-muted' }, 'Không còn thay đổi nào.')
            : summaryList((s) => {
                staged.delete(keyOf(s.classId, s.userId));
                cellRefresh.get(keyOf(s.classId, s.userId))?.();
                refreshGroups();
                drawBar();
                draw();
            }));
        draw();
        openModal({ title: 'Thay đổi đang chọn', body, actions: [{ label: 'Đóng', variant: 'secondary' }] });
    });

    applyBtn.addEventListener('click', () => {
        const c = counts();
        const note = c.request === 0 ? 'Các thay đổi có hiệu lực ngay.'
            : c.direct === 0 ? 'Mỗi thay đổi được gửi thành một đề nghị; giám khảo hoặc Super Admin sẽ duyệt.'
                : 'Nội dung "Thêm ngay" có hiệu lực ngay, nội dung "Gửi đề nghị" được gửi đi chờ duyệt.';
        openModal({
            title: `Xác nhận ${c.all.length} thay đổi`,
            body: [h('p', null, note), summaryList(null)],
            actions: [
                { label: 'Hủy', variant: 'secondary' },
                { label: applyBtn.textContent, onClick: (close) => { close(); submit(); } },
            ],
        });
    });

    async function submit() {
        const items = [...staged.values()];
        applyBtn.disabled = true;
        const failures = [];
        let applied = 0;
        let requested = 0;
        try {
            for (let i = 0; i < items.length; i += 300) {
                const chunk = items.slice(i, i + 300);
                const res = await api('/assignments/apply', { method: 'POST',
                    body: { changes: chunk.map((s) => ({ classId: s.classId, userId: s.userId, action: s.action })) } });
                applied += res.applied;
                requested += res.requested;
                res.results.forEach((r, k) => {
                    const s = chunk[k];
                    if (r.status === 'ERROR') failures.push({ ...s, message: r.message });
                    else staged.delete(keyOf(s.classId, s.userId));
                });
            }
        } catch (e) {
            toast(e.message, { type: 'error' });
        } finally {
            applyBtn.disabled = false;
        }
        const parts = [];
        if (applied) parts.push(`đã áp dụng ${applied} thay đổi`);
        if (requested) parts.push(`đã gửi ${requested} đề nghị`);
        if (parts.length) toast(parts.join(', ').replace(/^./, (ch) => ch.toUpperCase()) + '.', { type: 'success' });
        if (failures.length) {
            openModal({ title: `${failures.length} thay đổi không thực hiện được`, body: [
                h('p', null, 'Các thay đổi này vẫn đang được chọn để bạn xem lại.'),
                h('ul', { class: 'assign-errors' }, failures.map((f) => h('li', null, h('strong', null, `${f.who} · ${f.what}`), h('span', { class: 'cell-sub' }, f.message))))],
                actions: [{ label: 'Đóng', variant: 'secondary' }] });
        }
        drawBar();
        // Tải lại nhưng giữ trang đang xem.
        const keep = pages[localPage]?.[0] ?? 0;
        await load();
        localPage = Math.max(0, pages.findIndex(([s, e]) => keep >= s && keep < e));
        showPage();
    }

    append(container, [head, filterBox, viewerNote, legend, frame, bar]);
    drawBar();
    await loadUnits();
    await load();
}
