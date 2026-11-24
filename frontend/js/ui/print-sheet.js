import { currentUser } from '../core/auth.js';
import { formatDateTime } from '../core/format.js';
import { h } from './escape.js';
import { icon } from './icons.js';

/**
 * Trang in dùng chung, cùng bố cục với file Excel của hệ thống (common/excel/ExcelTemplate): dải tên hệ thống, dải
 * tiêu đề viết hoa, các dòng thông tin "Nhãn: giá trị", dòng ghi chú nghiêng, rồi bảng kẻ ô có hàng tiêu đề đậm.
 * Trên màn hình dải đầu nền xanh hải quân như file Excel; bản in (print.css) chuyển hết về đen trắng.
 * Chân mỗi trang in "Trang x / y" và dòng người in, thời gian in.
 */

const APP_NAME = 'HỆ THỐNG QUẢN LÝ HUẤN LUYỆN';

/**
 * @param {{title: string, info?: (string|null)[], note?: string, landscape?: boolean, children?: any[]}} o
 *   landscape: khổ A4 ngang (bảng nhiều cột như file Excel), mặc định A4 dọc.
 */
export function printSheet({ title, info = [], note, landscape = false, children = [] }) {
    const me = currentUser();
    const printedBy = `In lúc ${formatDateTime(new Date().toISOString())}${me ? ` · Người in: ${me.displayName}` : ''}`;
    return h('article', { class: ['print-sheet', landscape ? 'print-landscape' : null], 'aria-label': title },
        h('header', { class: 'ps-head' },
            h('p', { class: 'ps-app' }, APP_NAME),
            h('h1', { class: 'ps-title' }, title.toUpperCase())),
        info.filter(Boolean).length ? h('div', { class: 'ps-info' }, info.filter(Boolean).map((line) => h('p', null, line))) : null,
        note ? h('p', { class: 'ps-note' }, note) : null,
        children,
        h('p', { class: 'ps-foot' }, printedBy));
}

/** Tiêu đề mục (như dòng "section" của sheet hướng dẫn). */
export function printSection(text) {
    return h('h2', { class: 'ps-section' }, text);
}

/**
 * Bảng kẻ ô. Hàng tiêu đề lặp lại ở đầu mỗi trang in (thead).
 * @param {{caption?: string, columns: {label: string, cls?: string}[], rows: any[][], emptyText?: string}} o
 *   cls: 'num' (căn phải, số), 'center', 'nowrap', 'wide' (cột chữ dài được xuống dòng).
 */
export function printTable({ caption, columns, rows, emptyText = 'Không có dữ liệu.' }) {
    return h('div', { class: 'ps-table-wrap' },
        h('table', { class: 'ps-table' },
            caption ? h('caption', { class: 'visually-hidden' }, caption) : null,
            h('thead', null, h('tr', null, columns.map((c) => h('th', { scope: 'col', class: c.cls || null }, c.label)))),
            h('tbody', null, rows.length === 0
                ? h('tr', null, h('td', { colspan: String(columns.length), class: 'ps-empty' }, emptyText))
                : rows.map((r) => h('tr', null, r.map((cell, i) => h('td', { class: columns[i]?.cls || null }, cell ?? '')))))));
}

/** Bảng thông tin hai cặp "nhãn | giá trị" mỗi hàng (như sheet hướng dẫn của file Excel). */
export function printFacts(pairs) {
    const list = pairs.filter(Boolean);
    const rows = [];
    for (let i = 0; i < list.length; i += 2) rows.push(list.slice(i, i + 2));
    return h('div', { class: 'ps-table-wrap' },
        h('table', { class: 'ps-table ps-facts' },
            h('tbody', null, rows.map((pair) => h('tr', null, pair.map(([label, value]) => [
                h('th', { scope: 'row' }, label), h('td', { colspan: pair.length === 1 ? '3' : null }, value || '')]))))));
}

/**
 * Thanh trên trang in (không in ra): nút In, chú thích khổ giấy; đang in theo bộ lọc thì thêm nút "In tất cả".
 * @param {{landscape?: boolean, allHref?: string|null}} o allHref: đường dẫn trang in không kèm bộ lọc
 */
export function printToolbar({ landscape = false, allHref = null } = {}) {
    return h('div', { class: 'btn-row page-actions no-print print-toolbar' },
        h('button', { type: 'button', class: 'btn', onclick: () => window.print() }, icon('log', { small: true }), h('span', null, 'In trang này')),
        allHref ? h('a', { class: 'btn btn-secondary', href: allHref }, 'In tất cả (bỏ lọc)') : null,
        h('span', { class: 'field-hint' }, `Khổ A4 ${landscape ? 'ngang' : 'dọc'}. Có thể chọn "Lưu dưới dạng PDF" trong hộp thoại in.`));
}

/**
 * Dòng mô tả bộ lọc ghi trên bản in, ví dụ "Bộ lọc: Tình trạng: Đã có điểm · Xếp loại: Giỏi, Khá"; null khi không lọc.
 * @param {[string, string[]][]} parts nhãn và các giá trị đã chọn của từng ô lọc
 */
export function filterLine(parts) {
    const used = parts.filter(([, values]) => values.length > 0).map(([label, values]) => `${label}: ${values.join(', ')}`);
    return used.length ? `Bộ lọc: ${used.join('   ·   ')}` : null;
}

/**
 * Nút mở trang in kèm bộ lọc đang chọn ở trang nguồn; gọi update mỗi khi bộ lọc đổi. withIcon false khi các nút cạnh
 * nó chỉ có chữ.
 * @returns {{element: HTMLElement, update: (query: string) => void}} query: chuỗi tham số của bộ lọc ('' là in tất cả)
 */
export function printLink(href, label, { withIcon = true } = {}) {
    const text = h('span', null, label);
    const element = h('a', { class: 'btn btn-secondary', href }, withIcon ? icon('log', { small: true }) : null, text);
    return {
        element,
        update: (query) => {
            element.setAttribute('href', href + query);
            text.textContent = query ? `${label} theo bộ lọc` : label;
        },
    };
}
