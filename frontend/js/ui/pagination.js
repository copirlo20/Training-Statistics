import { h, replace } from './escape.js';
import { icon } from './icons.js';

/**
 * Thanh phân trang: "1–20 / 125" và nút trang trước, trang sau.
 * @param {(page: number) => void} onChange page tính từ 0
 */
export function createPagination(onChange) {
    const info = h('span', { class: 'page-info', 'aria-live': 'polite' });
    const prev = h('button', { type: 'button', class: 'btn btn-secondary', 'aria-label': 'Trang trước' },
        icon('chevron-left', { small: true }), h('span', null, 'Trước'));
    const next = h('button', { type: 'button', class: 'btn btn-secondary', 'aria-label': 'Trang sau' },
        h('span', null, 'Sau'), icon('chevron-right', { small: true }));
    const element = h('div', { class: 'pagination' }, info, h('div', { class: 'btn-row' }, prev, next));

    let state = { page: 0, size: 20, total: 0 };
    prev.addEventListener('click', () => { if (state.page > 0) onChange(state.page - 1); });
    next.addEventListener('click', () => { if ((state.page + 1) * state.size < state.total) onChange(state.page + 1); });

    const update = ({ page, size, total }) => {
        state = { page, size, total };
        const pages = Math.max(1, Math.ceil(total / size));
        const from = total === 0 ? 0 : page * size + 1;
        const to = Math.min(total, (page + 1) * size);
        replace(info, total === 0 ? 'Không có bản ghi' : `${from}–${to} / ${total} · Trang ${page + 1}/${pages}`);
        prev.disabled = page <= 0;
        next.disabled = (page + 1) * size >= total;
        element.hidden = total <= size && page === 0;
    };

    return { element, update };
}
