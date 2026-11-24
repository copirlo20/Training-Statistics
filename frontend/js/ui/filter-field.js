import { h } from './escape.js';

/** Một ô của thanh lọc: nhãn gắn với điều khiển theo id. */
export function filterField(id, label, control) {
    return h('div', { class: 'field' }, h('label', { for: id }, label), control);
}

/** Ô chọn một giá trị của thanh lọc; mục đầu (giá trị rỗng) là "tất cả", tức không lọc. */
export function filterSelect(id, options, placeholder, value = '') {
    const el = h('select', { id, class: 'select' },
        h('option', { value: '' }, placeholder), options.map((o) => h('option', { value: o.value }, o.label)));
    el.value = value;
    return el;
}
