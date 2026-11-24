import { h, replace } from './escape.js';
import { createPagination } from './pagination.js';

/**
 * Bảng dữ liệu có phân trang phía máy chủ, sắp xếp, trạng thái đang tải, rỗng và lỗi.
 * Dưới 600px mỗi dòng hiển thị như một thẻ "nhãn: giá trị" (CSS, cùng một DOM, mỗi ô có data-label).
 * Từ 600px đến dưới 1024px, cột có priority 3 bị ẩn.
 *
 * column: { key, label, render?(row) => Node|string, priority?: 1|2|3, sortable?: boolean, className?: string,
 *           actions?: boolean (ô chứa nút, không có nhãn ở dạng thẻ) }
 * load({page, size, sort, dir}) => Promise<{items, page, size, total}>
 * rowClass?(row) => string|null: lớp thêm cho dòng (ví dụ làm nổi bật dòng cần chú ý; luôn kèm chữ trong ô, không chỉ màu)
 */
export function createTable({ columns, load, pageSize = 20, emptyText = 'Chưa có dữ liệu.', caption, rowClass }) {
    const state = { page: 0, size: pageSize, sort: null, dir: 'asc' };
    const tbody = h('tbody');
    const headCells = columns.map((c) => {
        const th = h('th', { scope: 'col', class: [c.priority === 3 ? 'col-p3' : null, c.className] });
        if (c.sortable) {
            const mark = h('span', { class: 'sort-mark', 'aria-hidden': 'true' });
            th.appendChild(h('button', {
                type: 'button',
                class: 'th-sort',
                onclick: () => {
                    state.dir = state.sort === c.key && state.dir === 'asc' ? 'desc' : 'asc';
                    state.sort = c.key;
                    state.page = 0;
                    reload();
                },
            }, c.label, mark));
            c._mark = mark;
            c._th = th;
        } else {
            th.textContent = c.label;
        }
        return th;
    });

    const table = h('table', { class: 'data' },
        caption ? h('caption', { class: 'visually-hidden' }, caption) : null,
        h('thead', null, h('tr', null, headCells)),
        tbody);
    const pagination = createPagination((page) => { state.page = page; reload(); });
    const element = h('div', { class: 'data-table' }, h('div', { class: 'table-scroll' }, table), pagination.element);

    const messageRow = (content) => h('tr', { class: 'row-message' },
        h('td', { class: 'cell-message', colspan: columns.length }, content));

    const updateSortMarks = () => {
        for (const c of columns) {
            if (!c.sortable) continue;
            const active = state.sort === c.key;
            c._mark.textContent = active ? (state.dir === 'asc' ? '▲' : '▼') : '';
            c._th.setAttribute('aria-sort', active ? (state.dir === 'asc' ? 'ascending' : 'descending') : 'none');
        }
    };

    // Bọc nội dung ô trong một phần tử: ở dạng thẻ (dưới 600px) ô là lưới 2 cột "nhãn | giá trị",
    // nếu nội dung có nhiều phần tử con thì mỗi phần tử sẽ rơi vào một ô lưới riêng.
    const cellValue = (content) => h('div', { class: 'cell-value' }, content);

    let requestId = 0;
    async function reload() {
        const id = ++requestId;
        updateSortMarks();
        element.setAttribute('aria-busy', 'true');
        replace(tbody, messageRow(h('span', { class: 'loading' }, 'Đang tải...')));
        try {
            const result = await load({ page: state.page, size: state.size, sort: state.sort, dir: state.dir });
            if (id !== requestId) return;
            const items = result?.items || [];
            if (items.length === 0) {
                replace(tbody, messageRow(emptyText));
            } else {
                replace(tbody, items.map((row) => h('tr', { class: rowClass ? rowClass(row) : null }, columns.map((c) => h('td', {
                    'data-label': c.label,
                    class: [c.priority === 3 ? 'col-p3' : null, c.actions ? 'cell-actions' : null, c.className],
                }, cellValue(c.render ? c.render(row) : (row[c.key] ?? '')))))));
            }
            pagination.update({ page: result?.page ?? state.page, size: result?.size ?? state.size, total: result?.total ?? items.length });
        } catch (err) {
            if (id !== requestId) return;
            replace(tbody, messageRow(h('span', null,
                err?.message || 'Không tải được dữ liệu.', ' ',
                h('button', { type: 'button', class: 'btn btn-secondary', onclick: () => reload() }, 'Thử lại'))));
            pagination.update({ page: 0, size: state.size, total: 0 });
        } finally {
            if (id === requestId) element.removeAttribute('aria-busy');
        }
    }

    return {
        element,
        /** Tải lại; resetPage = true để về trang đầu (khi đổi bộ lọc). */
        reload: (resetPage = false) => {
            if (resetPage) state.page = 0;
            return reload();
        },
    };
}
