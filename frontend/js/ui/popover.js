import { h } from './escape.js';

/**
 * Khung thả xuống gắn vào một nút (thông báo, menu người dùng trên thanh trên). Mở bằng chạm hoặc bàn phím, không
 * phụ thuộc hover. Đóng khi bấm ra ngoài, nhấn Esc (trả focus về nút), hoặc đổi trang. Mỗi lúc chỉ mở một khung.
 *
 * @param {{trigger: HTMLElement, panel: HTMLElement, onOpen?: () => void, onClose?: () => void, className?: string}} options
 * @returns {{element: HTMLElement, open: () => void, close: () => void}}
 */
let current = null;

export function createPopover({ trigger, panel, onOpen, onClose, className }) {
    const id = 'pop-' + Math.random().toString(36).slice(2, 8);
    panel.id = id;
    panel.hidden = true;
    panel.classList.add('menu-panel');
    trigger.setAttribute('aria-haspopup', 'true');
    trigger.setAttribute('aria-expanded', 'false');
    trigger.setAttribute('aria-controls', id);
    const element = h('div', { class: ['header-menu', className] }, trigger, panel);

    const onPointer = (e) => { if (!element.contains(e.target)) close(); };
    const onKey = (e) => {
        if (e.key === 'Escape') {
            close();
            trigger.focus();
        }
    };

    function open() {
        if (current && current !== api) current.close();
        current = api;
        panel.hidden = false;
        trigger.setAttribute('aria-expanded', 'true');
        document.addEventListener('pointerdown', onPointer, true);
        document.addEventListener('keydown', onKey);
        window.addEventListener('hashchange', close);
        onOpen?.();
    }

    function close() {
        if (panel.hidden) return;
        panel.hidden = true;
        trigger.setAttribute('aria-expanded', 'false');
        document.removeEventListener('pointerdown', onPointer, true);
        document.removeEventListener('keydown', onKey);
        window.removeEventListener('hashchange', close);
        if (current === api) current = null;
        onClose?.();
    }

    trigger.addEventListener('click', () => (panel.hidden ? open() : close()));
    const api = { element, open, close };
    return api;
}
