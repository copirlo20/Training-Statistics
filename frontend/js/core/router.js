import { h, replace } from '../ui/escape.js';
import { icon } from '../ui/icons.js';
import { hasRole } from './auth.js';

/**
 * Router theo hash (#/duong-dan). Mỗi route trỏ tới một module trang xuất render(container, params).
 * Trang được nạp lười (import động) để chỉ tải mã của trang đang xem.
 * Kiểm tra vai trò ở đây chỉ để không hiện trang vô nghĩa; quyền thật luôn do máy chủ quyết định.
 */

const routes = [];
let renderToken = 0;

/**
 * @param {string} pattern ví dụ '/admin/audit' hoặc '/users/:id'
 * @param {{title: string, roles?: string[], load: () => Promise<{render: Function}>}} def
 */
export function route(pattern, def) {
    const keys = [];
    const regex = new RegExp('^' + pattern.replace(/:([a-zA-Z]+)/g, (_, k) => {
        keys.push(k);
        return '([^/]+)';
    }) + '/?$');
    routes.push({ pattern, regex, keys, ...def });
}

function currentPath() {
    const raw = location.hash.replace(/^#/, '');
    return raw.split('?')[0] || '/';
}

/** Tham số sau dấu ? của hash (#/classes?periodId=3), dạng URLSearchParams. */
export function currentQuery() {
    const raw = location.hash.replace(/^#/, '');
    const i = raw.indexOf('?');
    return new URLSearchParams(i < 0 ? '' : raw.slice(i + 1));
}

/**
 * Bộ lọc thành chuỗi tham số "?a=1&b=x&b=y" (mảng thành tham số lặp, bỏ giá trị rỗng); '' khi không lọc gì.
 * Dùng cho đường dẫn trang in và để giữ bộ lọc trên địa chỉ trang.
 */
export function toQueryString(filters) {
    const p = new URLSearchParams();
    for (const [key, value] of Object.entries(filters)) {
        for (const v of Array.isArray(value) ? value : [value]) {
            if (v !== '' && v !== null && v !== undefined) p.append(key, v);
        }
    }
    const s = p.toString();
    return s ? `?${s}` : '';
}

/** Đọc bộ lọc từ tham số của hash theo khuôn defaults: khóa là mảng nhận nhiều giá trị, khóa chuỗi nhận một. */
export function readQuery(defaults) {
    const q = currentQuery();
    return Object.fromEntries(Object.entries(defaults).map(([key, d]) => {
        if (!q.has(key)) return [key, d];
        return [key, Array.isArray(d) ? q.getAll(key) : q.get(key)];
    }));
}

/** Ghi bộ lọc lên địa chỉ trang đang xem mà không vẽ lại trang (quay lại từ trang in vẫn giữ bộ lọc). */
export function replaceQuery(filters) {
    history.replaceState(null, '', `#${currentPath()}${toQueryString(filters)}`);
}

export function navigate(path) {
    location.hash = '#' + path;
}

function match(path) {
    for (const r of routes) {
        const m = path.match(r.regex);
        if (m) {
            const params = {};
            r.keys.forEach((k, i) => { params[k] = decodeURIComponent(m[i + 1]); });
            return { route: r, params };
        }
    }
    return null;
}

function statePage(iconName, title, text) {
    return h('div', { class: 'state-page' },
        icon(iconName),
        h('h1', null, title),
        h('p', { class: 'text-muted' }, text),
        h('a', { class: 'btn btn-secondary', href: '#/' }, 'Về trang chủ'));
}

/**
 * @param {HTMLElement} container vùng nội dung chính
 * @param {(path: string) => void} onNavigate gọi sau mỗi lần đổi trang (để tô sáng menu)
 */
export function start(container, onNavigate) {
    const render = async () => {
        const token = ++renderToken;
        const path = currentPath();
        const found = match(path);
        onNavigate?.(path);
        window.scrollTo(0, 0);

        if (!found) {
            document.title = 'Không tìm thấy - Quản lý huấn luyện';
            replace(container, statePage('alert', 'Không tìm thấy trang', 'Đường dẫn không tồn tại.'));
            return;
        }
        if (!hasRole(found.route.roles)) {
            document.title = 'Không có quyền - Quản lý huấn luyện';
            replace(container, statePage('shield', 'Không có quyền', 'Bạn không có quyền xem trang này.'));
            return;
        }

        document.title = `${found.route.title} - Quản lý huấn luyện`;
        replace(container, h('div', { class: 'loading', 'aria-busy': 'true' }, 'Đang tải...'));
        try {
            const mod = await found.route.load();
            if (token !== renderToken) return;   // người dùng đã chuyển trang khác trong lúc tải
            const page = h('div', { class: 'page' });
            replace(container, page);
            await mod.render(page, found.params);
            if (token === renderToken) container.focus({ preventScroll: true });
        } catch (e) {
            if (token !== renderToken) return;
            console.error(e);
            replace(container, statePage('alert', 'Không tải được trang', e?.message || 'Vui lòng thử lại.'));
        }
    };
    window.addEventListener('hashchange', render);
    return render();
}
