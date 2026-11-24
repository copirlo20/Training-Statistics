/** Kho trạng thái nhỏ dùng chung giữa các module: get, set. Không có biến toàn cục nào khác. */

const state = new Map();

export function get(key) {
    return state.get(key);
}

export function set(key, value) {
    state.set(key, value);
}
