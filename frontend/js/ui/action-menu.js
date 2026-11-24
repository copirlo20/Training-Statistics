import { h } from './escape.js';
import { icon } from './icons.js';
import { createPopover } from './popover.js';

/**
 * Nút thả xuống gom các thao tác ít dùng của trang chi tiết (kiểu Admindek), để hàng nút không tràn thành nhiều dòng.
 * Mở bằng chạm hoặc bàn phím; mỗi mục là một nút đủ vùng chạm 44px.
 *
 * @param {string} label nhãn nút, ví dụ "Thao tác khác"
 * @param {({label: string, icon?: string, href?: string, onClick?: () => void, tone?: 'danger'} | null)[]} items
 */
export function actionMenu(label, items) {
    const list = items.filter(Boolean);
    if (list.length === 0) return null;
    const trigger = h('button', { type: 'button', class: 'btn btn-secondary' },
        h('span', null, label), icon('chevron-down', { small: true }));
    let popover;
    const panel = h('div', { role: 'menu', 'aria-label': label },
        h('ul', { class: 'menu-list' }, list.map((a) => {
            const content = [a.icon ? icon(a.icon, { small: true }) : null, a.label];
            const cls = ['menu-item', a.tone === 'danger' ? 'menu-item-danger' : null];
            return h('li', null, a.href
                ? h('a', { class: cls, href: a.href, role: 'menuitem' }, content)
                : h('button', { type: 'button', class: cls, role: 'menuitem', onclick: () => { popover.close(); a.onClick(); } }, content));
        })));
    popover = createPopover({ trigger, panel, className: 'actions-menu' });
    return popover.element;
}
