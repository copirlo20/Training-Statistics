import { h } from './escape.js';

/**
 * Đầu trang (.page-head): tiêu đề, dòng mô tả và nút thao tác bên phải.
 * @param {string} title
 * @param {*} [sub] nội dung dòng mô tả (chữ, phần tử hoặc mảng); null thì không có dòng mô tả
 * @param {Node|Node[]|null} [actions] một nút đứng riêng, hoặc mảng nút (gói trong .btn-row, mảng rỗng thì bỏ)
 */
export function pageHead(title, sub = null, actions = null) {
    const buttons = Array.isArray(actions)
        ? (actions.length ? h('div', { class: 'btn-row' }, actions) : null)
        : actions;
    return h('div', { class: 'page-head' },
        h('div', null, h('h1', null, title), sub === null ? null : h('p', { class: 'page-sub' }, sub)),
        buttons);
}
