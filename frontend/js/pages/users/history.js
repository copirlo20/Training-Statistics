import { formatDate, searchKey } from '../../core/format.js';
import { formatScore, GRADE, GRADE_ORDER, labelsOf, PARTICIPANT_STATE } from '../../core/refdata.js';
import { readQuery, replaceQuery, toQueryString } from '../../core/router.js';
import { h, replace } from '../../ui/escape.js';
import { filterField } from '../../ui/filter-field.js';
import { multiSelect } from '../../ui/multi-select.js';
import { filterLine, printLink } from '../../ui/print-sheet.js';
import { createTable } from '../../ui/table.js';
import { stateCell } from '../classes/results.js';

let counter = 0;

/** Bộ lọc lịch sử điểm của một người, dùng chung cho thẻ lịch sử và trang in thông tin cá nhân. */
export function emptyHistoryFilter() {
    return { q: '', periods: [], states: [], grades: [], from: '', to: '' };
}

/** Nội dung chưa đặt ngày kiểm tra không khớp khi đã chọn khoảng ngày. */
export function historyMatches(c, f) {
    const date = c.classInfo.examDate;
    const inRange = (!f.from && !f.to) || (!!date && (!f.from || date >= f.from) && (!f.to || date <= f.to));
    const key = searchKey(f.q);
    return inRange
        && (!key || searchKey(c.classInfo.name).includes(key))
        && (f.periods.length === 0 || f.periods.includes(c.classInfo.periodName))
        && (f.states.length === 0 || f.states.includes(c.state))
        && (f.grades.length === 0 || (c.result && f.grades.includes(c.result.grade)));
}

/** Dòng "Bộ lọc: ..." cho bản in; null khi không lọc. */
export function historyFilterText(f) {
    let range = [];
    if (f.from || f.to) range = [[f.from && `từ ${formatDate(f.from)}`, f.to && `đến ${formatDate(f.to)}`].filter(Boolean).join(' ')];
    return filterLine([
        ['Đợt kiểm tra', f.periods],
        ['Ngày kiểm tra', range],
        ['Tình trạng', labelsOf(PARTICIPANT_STATE, f.states)],
        ['Xếp loại', labelsOf(GRADE, f.grades)],
        ['Tìm nội dung', f.q ? [`"${f.q}"`] : []],
    ]);
}

/**
 * Thẻ lịch sử điểm kèm nút in thông tin cá nhân theo đúng bộ lọc của thẻ. Bộ lọc đọc từ địa chỉ trang và ghi lại mỗi
 * lần đổi, nên quay lại từ trang in vẫn giữ. Dùng cho trang Hồ sơ và trang chi tiết người dùng.
 * @returns {{printButton: HTMLElement, card: (title: string, items: object[], emptyText: string) => HTMLElement}}
 */
export function historyWithPrint(userId, printLabel) {
    const filter = readQuery(emptyHistoryFilter());
    const print = printLink(`#/users/${userId}/print`, printLabel);
    const onFilter = (f) => {
        replaceQuery(f);
        print.update(toQueryString(f));
    };
    return {
        printButton: print.element,
        card: (title, items, emptyText) => historyCard(title, items, emptyText, { filter, onFilter }),
    };
}

/**
 * Thẻ "nội dung kiểm tra đã tham gia và điểm" (hồ sơ của tôi, chi tiết người dùng), gom theo đợt kiểm tra, đợt mới nhất
 * trước. Tìm theo tên nội dung; lọc theo khoảng ngày kiểm tra và nhiều lựa chọn cùng lúc theo đợt, tình trạng, xếp loại
 * (lọc ở trình duyệt vì dữ liệu của một người đã tải đủ). items lấy từ GET /me/classes hoặc GET /users/{id}/history
 * (đã bỏ nội dung bị gỡ khỏi).
 * @param {{filter?: object, onFilter?: (filter: object) => void}} [o] filter: bộ lọc ban đầu, thẻ sửa trực tiếp trên
 *   đối tượng này nên giữ được qua các lần vẽ lại trang; onFilter gọi sau mỗi lần lọc (để cập nhật nút in).
 */
function historyCard(title, items, emptyText, { filter = emptyHistoryFilter(), onFilter } = {}) {
    const n = ++counter;
    const tested = items.filter((c) => c.result).length;
    const card = h('section', { class: 'card' },
        h('h2', null, title),
        items.length ? h('p', { class: 'field-hint' }, `${items.length} nội dung, ${tested} đã có điểm.`) : null);
    if (items.length === 0) {
        card.append(h('p', { class: 'text-muted' }, emptyText));
        return card;
    }

    const periodNames = [...new Set(items.map((c) => c.classInfo.periodName))];
    const filters = filter;

    const search = h('input', { id: `hist-q-${n}`, class: 'input', type: 'search', autocomplete: 'off',
        placeholder: 'Tên nội dung kiểm tra', value: filters.q });
    const periodSel = multiSelect({ id: `hist-period-${n}`, allLabel: 'Tất cả đợt', unit: 'đợt', values: filters.periods,
        options: periodNames.map((p) => ({ value: p, label: p })), onChange: (v) => { filters.periods = v; draw(); } });
    const stateSel = multiSelect({ id: `hist-state-${n}`, allLabel: 'Mọi tình trạng', unit: 'tình trạng', searchable: false,
        values: filters.states, options: Object.entries(PARTICIPANT_STATE).map(([value, s]) => ({ value, label: s.label })),
        onChange: (v) => { filters.states = v; draw(); } });
    const gradeSel = multiSelect({ id: `hist-grade-${n}`, allLabel: 'Mọi xếp loại', unit: 'xếp loại', searchable: false,
        values: filters.grades, options: GRADE_ORDER.map((g) => ({ value: g, label: GRADE[g].label })),
        onChange: (v) => { filters.grades = v; draw(); } });
    let timer;
    search.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(() => { filters.q = search.value.trim(); draw(); }, 200); });
    // Lọc theo ngày kiểm tra (từ ngày, đến ngày; để trống một đầu là không giới hạn đầu đó).
    const fromInput = h('input', { id: `hist-from-${n}`, class: 'input', type: 'date', value: filters.from });
    const toInput = h('input', { id: `hist-to-${n}`, class: 'input', type: 'date', value: filters.to });
    const dateError = h('div', { class: 'field-error', role: 'alert' });
    const onDate = () => {
        if (fromInput.value && toInput.value && fromInput.value > toInput.value) {
            replace(dateError, 'Đến ngày phải sau hoặc bằng từ ngày.');
            return;
        }
        replace(dateError, null);
        Object.assign(filters, { from: fromInput.value, to: toInput.value });
        draw();
    };
    fromInput.addEventListener('change', onDate);
    toInput.addEventListener('change', onDate);
    const toolbar = h('div', { class: 'toolbar toolbar-dense hist-toolbar' },
        filterField(search.id, 'Tìm nội dung', search),
        filterField(`hist-period-${n}`, 'Đợt kiểm tra', periodSel.element),
        filterField(fromInput.id, 'Ngày kiểm tra từ', fromInput),
        filterField(toInput.id, 'Đến ngày', toInput),
        filterField(`hist-state-${n}`, 'Tình trạng', stateSel.element),
        filterField(`hist-grade-${n}`, 'Xếp loại', gradeSel.element),
        dateError);
    const summary = h('p', { class: 'field-hint', 'aria-live': 'polite' });
    const groupsSlot = h('div', { class: 'hist-groups' });

    function draw() {
        const shown = items.filter((c) => historyMatches(c, filters));
        onFilter?.(filters);
        const groups = new Map();
        for (const c of shown) {
            const k = c.classInfo.periodName;
            if (!groups.has(k)) groups.set(k, []);
            groups.get(k).push(c);
        }
        const latest = (list) => list.reduce((m, c) => (c.classInfo.examDate && c.classInfo.examDate > m ? c.classInfo.examDate : m), '');
        const ordered = [...groups.entries()].sort((a, b) => latest(b[1]).localeCompare(latest(a[1])));
        replace(summary, shown.length === items.length ? `${ordered.length} đợt kiểm tra.`
            : `Đang hiện ${shown.length}/${items.length} nội dung trong ${ordered.length} đợt.`);
        replace(groupsSlot, ordered.length === 0 ? h('p', { class: 'text-muted' }, 'Không có nội dung nào khớp bộ lọc.')
            : ordered.map(([name, list]) => groupBlock(name, list)));
    }

    function groupBlock(name, list) {
        const scored = list.filter((c) => c.result);
        const avg = scored.length ? Math.round(scored.reduce((s, c) => s + c.result.scoreX10, 0) / scored.length) : null;
        const table = createTable({
            caption: `Đợt ${name}`,
            emptyText: '',
            columns: [
                { key: 'class', label: 'Nội dung kiểm tra', render: (c) => h('a', { href: `#/classes/${c.classInfo.id}` }, c.classInfo.name) },
                { key: 'date', label: 'Ngày kiểm tra', className: 'hist-col-date', render: (c) => formatDate(c.classInfo.examDate) || '—' },
                { key: 'result', label: 'Kết quả', className: 'hist-col-result', render: stateCell },
            ],
            load: async () => ({ items: list, page: 0, size: Math.max(list.length, 1), total: list.length }),
        });
        table.reload();
        return h('section', { class: 'hist-group' },
            h('div', { class: 'hist-head' },
                h('h3', null, name),
                h('span', { class: 'cell-sub' }, `${list.length} nội dung · ${scored.length} có điểm`
                    + (avg !== null ? ` · trung bình ${formatScore(avg)}` : ''))),
            table.element);
    }

    card.append(toolbar, summary, groupsSlot);
    draw();
    return card;
}
