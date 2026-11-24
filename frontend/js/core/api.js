/**
 * Gọi API của máy chủ.
 * - Gửi kèm cookie phiên (cùng nguồn).
 * - Request ghi tự gắn token CSRF: đọc cookie XSRF-TOKEN, gửi lại trong header X-XSRF-TOKEN.
 *   Token đổi sau đăng nhập, nên luôn đọc cookie mới nhất, và thử lại một lần nếu máy chủ báo CSRF_INVALID.
 * - 401 thì chuyển về trang đăng nhập (trừ khi gọi với { allow401: true }). 403 PASSWORD_CHANGE_REQUIRED (mật khẩu do
 *   người khác đặt, chưa tự đổi) cũng về trang đăng nhập, nơi hiện form đặt mật khẩu mới.
 * - Lỗi trả về là ApiError mang { status, code, message, fieldErrors }.
 */

const BASE = '/api/v1';
const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';

export class ApiError extends Error {
    constructor(status, body) {
        super(body?.message || 'Đã có lỗi xảy ra.');
        this.status = status;
        this.code = body?.code || 'UNKNOWN';
        this.fieldErrors = Array.isArray(body?.fieldErrors) ? body.fieldErrors : [];
    }
}

function readCookie(name) {
    const prefix = name + '=';
    for (const part of document.cookie.split(';')) {
        const p = part.trim();
        if (p.startsWith(prefix)) return decodeURIComponent(p.slice(prefix.length));
    }
    return null;
}

async function csrfToken(forceRefresh = false) {
    let token = forceRefresh ? null : readCookie(CSRF_COOKIE);
    if (!token) {
        const res = await fetch(BASE + '/auth/csrf', { credentials: 'same-origin', headers: { Accept: 'application/json' } });
        if (res.ok) {
            const data = await res.json();
            token = readCookie(CSRF_COOKIE) || data.token;
        }
    }
    return token;
}

export function redirectToLogin() {
    const next = location.hash && location.hash !== '#/' ? '?next=' + encodeURIComponent(location.hash) : '';
    location.replace('login.html' + next);
}

/**
 * @param {string} path    đường dẫn sau /api/v1, ví dụ '/me'
 * @param {object} options { method, body, query, allow401 }
 */
export async function api(path, options = {}) {
    // form: FormData (tải file lên). Không tự đặt Content-Type để trình duyệt thêm boundary của multipart.
    const { method = 'GET', body, form, query, allow401 = false } = options;
    const url = new URL(BASE + path, location.origin);
    if (query) {
        for (const [k, v] of Object.entries(query)) {
            if (v !== null && v !== undefined && v !== '') url.searchParams.set(k, String(v));
        }
    }
    const send = async (forceCsrf) => {
        const headers = { Accept: 'application/json' };
        if (body !== undefined) headers['Content-Type'] = 'application/json';
        if (method !== 'GET' && method !== 'HEAD') headers[CSRF_HEADER] = await csrfToken(forceCsrf);
        return fetch(url, {
            method,
            headers,
            credentials: 'same-origin',
            body: form !== undefined ? form : body === undefined ? undefined : JSON.stringify(body),
        });
    };

    let res;
    try {
        res = await send(false);
        if (res.status === 403) {
            const data = await safeJson(res.clone());
            if (data?.code === 'CSRF_INVALID') res = await send(true);
        }
    } catch {
        throw new ApiError(0, { code: 'NETWORK', message: 'Không kết nối được máy chủ. Kiểm tra mạng rồi thử lại.' });
    }

    if (res.status === 204) return null;
    const data = await safeJson(res);
    if (res.ok) return data;
    if (res.status === 401 && !allow401) {
        redirectToLogin();
    }
    if (res.status === 403 && data?.code === 'PASSWORD_CHANGE_REQUIRED' && !location.pathname.endsWith('/login.html')) {
        redirectToLogin();
    }
    throw new ApiError(res.status, data);
}

async function safeJson(res) {
    const text = await res.text();
    if (!text) return null;
    try {
        return JSON.parse(text);
    } catch {
        return null;
    }
}
