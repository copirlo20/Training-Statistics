import { h } from './escape.js';
import { icon } from './icons.js';

/**
 * Nút thao tác ngay trên dòng của bảng (kiểu Admindek): nút biểu tượng không viền, vùng chạm vẫn đủ 44×44 px.
 * Mỗi nút có aria-label (trình đọc màn hình) và title (chú giải khi rê chuột, không phải cách duy nhất để biết nghĩa:
 * trên điện thoại bảng thành thẻ và cột này có nhãn "Thao tác").
 *
 * @param {({icon: string, label: string, href?: string, onClick?: () => void, tone?: 'danger'} | null)[]} items
 *        phần tử null bị bỏ qua, để viết điều kiện ngay trong danh sách
 */
export function rowActions(items) {
    const buttons = items.filter(Boolean).map((a) => {
        const props = { class: ['icon-btn', a.tone === 'danger' ? 'icon-btn-danger' : null], 'aria-label': a.label, title: a.label };
        return a.href
            ? h('a', { ...props, href: a.href }, icon(a.icon, { small: true }))
            : h('button', { ...props, type: 'button', onclick: a.onClick }, icon(a.icon, { small: true }));
    });
    return buttons.length ? h('div', { class: 'row-actions' }, buttons) : null;
}
