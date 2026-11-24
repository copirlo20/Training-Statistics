import { api } from './api.js';
import * as store from './store.js';

/** Vai trò hiển thị cho người dùng. */
const ROLE_LABELS = {
    SUPER_ADMIN: 'Super Admin',
    ADMIN: 'Admin',
    USER: 'Người dùng',
};

/** Nạp người đang đăng nhập. 401 thì api.js tự chuyển về trang đăng nhập. */
export async function loadMe() {
    const me = await api('/me');
    store.set('me', me);
    return me;
}

export function currentUser() {
    return store.get('me');
}

/** @param {string[]|null} roles danh sách vai trò được phép; null là mọi người đã đăng nhập */
export function hasRole(roles) {
    const me = currentUser();
    if (!me) return false;
    if (!roles || roles.length === 0) return true;
    return roles.includes(me.role);
}

export function roleLabel(me) {
    if (!me) return '';
    if (me.role === 'ADMIN' && me.adminLevel) return `Admin cấp ${me.adminLevel}`;
    return ROLE_LABELS[me.role] || me.role;
}

export async function logout() {
    try {
        await api('/auth/logout', { method: 'POST', allow401: true });
    } finally {
        location.replace('login.html');
    }
}
