import { h } from './escape.js';
import { icon } from './icons.js';

/**
 * Liên kết quay lại trang cha, đặt trước khối tiêu đề trang (.page-head) ở mọi trang con, để vị trí luôn cố định
 * ở góc trên bên trái và không lẫn với các nút thao tác bên phải.
 */
export function backLink(href, label) {
    return h('a', { class: 'back-link', href }, icon('chevron-left', { small: true }), h('span', null, label));
}
