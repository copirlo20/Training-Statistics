/**
 * Dựng DOM an toàn. Mọi dữ liệu từ máy chủ hoặc người dùng đi vào DOM qua đây (textContent), KHÔNG dùng innerHTML.
 *
 *   h('p', { class: 'note' }, 'Xin chào ', user.name)
 *   h('button', { type: 'button', onclick: () => ... }, 'Lưu')
 *
 * Thuộc tính:
 *   class, id, các thuộc tính HTML bình thường (giá trị null/false thì bỏ qua, true thì đặt rỗng)
 *   dataset: { key: value }
 *   on<sự kiện>: hàm xử lý (onclick, oninput, onsubmit...)
 * Con: chuỗi (thành text node), số, Node, mảng, null/undefined/false (bỏ qua).
 */
export function h(tag, props, ...children) {
    const el = document.createElement(tag);
    if (props) {
        for (const [key, value] of Object.entries(props)) {
            if (value === null || value === undefined || value === false) continue;
            if (key === 'dataset') {
                for (const [k, v] of Object.entries(value)) el.dataset[k] = String(v);
            } else if (key.startsWith('on') && typeof value === 'function') {
                el.addEventListener(key.slice(2), value);
            } else if (key === 'class') {
                el.className = Array.isArray(value) ? value.filter(Boolean).join(' ') : value;
            } else if (value === true) {
                el.setAttribute(key, '');
            } else {
                el.setAttribute(key, String(value));
            }
        }
    }
    append(el, children);
    return el;
}

/** Thêm con vào phần tử theo cùng quy tắc của h(). */
export function append(el, children) {
    for (const child of children.flat(Infinity)) {
        if (child === null || child === undefined || child === false || child === '') continue;
        el.appendChild(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return el;
}

/** Xóa toàn bộ con rồi thêm mới. */
export function replace(el, ...children) {
    el.replaceChildren();
    return append(el, children);
}
