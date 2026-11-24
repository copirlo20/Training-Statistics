import { h } from './escape.js';
import { icon } from './icons.js';

/**
 * Thông báo ngắn ở góc màn hình (trên thanh tab đáy khi ở điện thoại).
 * @param {string} message
 * @param {{type?: 'info'|'success'|'error', timeout?: number}} options lỗi giữ lâu hơn để kịp đọc
 */
export function toast(message, { type = 'info', timeout } = {}) {
    const region = document.getElementById('toast-region');
    if (!region) return;
    const duration = timeout ?? (type === 'error' ? 8000 : 4000);
    const close = () => el.remove();
    const el = h('div', { class: ['toast', `toast-${type}`], role: type === 'error' ? 'alert' : 'status' },
        h('span', { class: 'toast-text' }, message),
        h('button', { type: 'button', class: 'btn btn-icon btn-on-dark', 'aria-label': 'Đóng thông báo', onclick: close },
            icon('close', { small: true })));
    region.appendChild(el);
    if (duration > 0) setTimeout(close, duration);
}
