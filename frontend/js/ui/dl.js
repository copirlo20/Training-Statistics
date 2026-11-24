import { h } from './escape.js';

/** Một cặp nhãn, giá trị của danh sách mô tả (dl.dl). Giá trị trống hiện chữ mờ "Chưa có". */
export function dlRow(label, value) {
    return [h('dt', null, label), h('dd', null, value || h('span', { class: 'text-muted' }, 'Chưa có'))];
}
