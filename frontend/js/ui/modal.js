import { h } from './escape.js';
import { icon } from './icons.js';

/**
 * Hộp thoại. Dưới 1024px mở toàn màn hình, từ 1024px là cửa sổ giữa màn hình (xem components.css).
 * Giữ focus bên trong hộp thoại, Esc để đóng, trả focus về chỗ cũ khi đóng.
 *
 * @param {{title: string, body: Node|Node[], actions?: {label: string, variant?: string, onClick?: (close) => void}[],
 *          onClose?: () => void}} options
 * @returns {{close: () => void, element: HTMLElement}}
 */
export function openModal({ title, body, actions = [], onClose, size }) {
    const previous = document.activeElement;
    const titleId = 'modal-title-' + Math.random().toString(36).slice(2, 8);

    let closed = false;
    const close = () => {
        if (closed) return;
        closed = true;
        overlay.remove();
        document.removeEventListener('keydown', onKey, true);
        window.removeEventListener('hashchange', close);
        document.body.style.overflow = '';
        if (previous && typeof previous.focus === 'function') previous.focus();
        onClose?.();
    };

    const foot = actions.length === 0 ? null : h('div', { class: 'modal-foot' },
        actions.map((a) => h('button', {
            type: 'button',
            class: ['btn', a.variant ? `btn-${a.variant}` : null],
            onclick: () => (a.onClick ? a.onClick(close) : close()),
        }, a.label)));

    const dialog = h('div', { class: ['modal', size === 'wide' ? 'modal-wide' : null], role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': titleId },
        h('div', { class: 'modal-head' },
            h('h2', { id: titleId }, title),
            h('button', { type: 'button', class: 'btn btn-icon modal-close', 'aria-label': 'Đóng', onclick: close },
                icon('close'))),
        h('div', { class: 'modal-body' }, body),
        foot);

    const overlay = h('div', { class: 'modal-overlay', onclick: (e) => { if (e.target === overlay) close(); } }, dialog);

    const focusable = () => [...dialog.querySelectorAll(
        'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])')];

    const onKey = (e) => {
        if (e.key === 'Escape') {
            e.preventDefault();
            close();
        } else if (e.key === 'Tab') {
            const items = focusable();
            if (items.length === 0) return;
            const first = items[0];
            const last = items[items.length - 1];
            if (e.shiftKey && document.activeElement === first) {
                e.preventDefault();
                last.focus();
            } else if (!e.shiftKey && document.activeElement === last) {
                e.preventDefault();
                first.focus();
            }
        }
    };

    document.body.appendChild(overlay);
    document.body.style.overflow = 'hidden';
    document.addEventListener('keydown', onKey, true);
    // Đổi trang (kể cả nút Back) thì đóng hộp thoại, nếu không nó nằm đè lên trang mới.
    window.addEventListener('hashchange', close);
    const items = focusable();
    // Ưu tiên ô nhập đầu tiên, nếu không có thì nút đóng.
    (items.find((el) => el.matches('input, select, textarea')) || items[0])?.focus();

    return { close, element: dialog };
}

/**
 * Báo các mục lỗi sau một thao tác hàng loạt (lưu nhiều điểm, duyệt nhiều đề nghị).
 * @param {string} title @param {string} intro @param {{who: string, message: string}[]} failed
 */
export function failureReport(title, intro, failed) {
    openModal({
        title,
        body: [h('p', null, intro),
            h('ul', { class: 'assign-errors' }, failed.map((f) => h('li', null, h('strong', null, f.who), h('span', { class: 'cell-sub' }, f.message))))],
        actions: [{ label: 'Đóng', variant: 'secondary' }],
    });
}

/** Hộp thoại xác nhận. Trả Promise<boolean>. */
export function confirmDialog(message, { title = 'Xác nhận', okLabel = 'Đồng ý', cancelLabel = 'Hủy', danger = false } = {}) {
    return new Promise((resolve) => {
        let answered = false;
        openModal({
            title,
            body: h('p', null, message),
            actions: [
                { label: cancelLabel, variant: 'secondary', onClick: (close) => { answered = true; close(); resolve(false); } },
                { label: okLabel, variant: danger ? 'danger' : null, onClick: (close) => { answered = true; close(); resolve(true); } },
            ],
            onClose: () => { if (!answered) resolve(false); },
        });
    });
}
