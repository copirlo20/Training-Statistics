import { currentUser, hasRole, logout, roleLabel } from '../core/auth.js';
import { createBell } from './bell.js';
import { h, replace } from './escape.js';
import { icon } from './icons.js';
import { openModal } from './modal.js';
import { createPopover } from './popover.js';

/**
 * Điều hướng kiểu dashboard (tham khảo AdminKit, Hope UI), màu Hải quân:
 * - Từ 1024px: thanh bên đầy đủ, menu chia nhóm có tiêu đề nhóm; nút ☰ trên thanh trên thu gọn thành thanh biểu
 *   tượng (nhớ lựa chọn trong trình duyệt).
 * - 600 đến dưới 1024px: thanh bên chỉ có biểu tượng và nhãn nhỏ.
 * - Dưới 600px: thanh tab đáy với tối đa TAB_SLOTS mục chính, các mục còn lại và Đăng xuất nằm trong "Thêm".
 * Thanh trên: đường dẫn trang, chuông (khung xem nhanh thông báo), menu người dùng (hồ sơ, đổi mật khẩu, đăng xuất).
 * Mục chỉ hiện khi người dùng có vai trò phù hợp (quyền thật vẫn do máy chủ kiểm).
 */

const TAB_SLOTS = 4;
const COLLAPSE_KEY = 'ts.nav.collapsed';

/**
 * Danh sách mục điều hướng, theo thứ tự hiển thị. Chỉ khai báo trang đã có, không để liên kết chết.
 * section: nhóm trên thanh bên; roles: vai trò được thấy; show(me): điều kiện thêm (giám khảo là gán theo nội dung kiểm tra);
 * primary: được ưu tiên đưa lên thanh tab đáy của điện thoại; short: nhãn ngắn cho thanh tab đáy khi nhãn đầy đủ quá dài.
 */
const NAV_ITEMS = [
    { path: '/', label: 'Trang chủ', icon: 'home', section: 'Tổng quan', primary: true },
    { path: '/stats', label: 'Thống kê', icon: 'chart', section: 'Tổng quan', roles: ['ADMIN', 'SUPER_ADMIN'], primary: true },
    { path: '/notifications', label: 'Thông báo', icon: 'bell', section: 'Tổng quan' },
    { path: '/classes', label: 'Nội dung kiểm tra', short: 'Nội dung', icon: 'class', section: 'Huấn luyện', primary: true },
    { path: '/assign', label: 'Phân công', icon: 'users', section: 'Huấn luyện',
        show: (me) => me.role !== 'USER' || (me.teachingClasses || []).length > 0 },
    { path: '/requests', label: 'Đề nghị', icon: 'inbox', section: 'Huấn luyện',
        show: (me) => me.role !== 'USER' || (me.teachingClasses || []).length > 0 },
    { path: '/periods', label: 'Đợt kiểm tra', icon: 'calendar', section: 'Huấn luyện', roles: ['SUPER_ADMIN'] },
    { path: '/users', label: 'Người dùng', icon: 'users', section: 'Quản trị', roles: ['ADMIN', 'SUPER_ADMIN'], primary: true },
    { path: '/admin/units', label: 'Đơn vị', icon: 'unit', section: 'Quản trị', roles: ['SUPER_ADMIN'] },
    { path: '/admin/catalogs', label: 'Danh mục', icon: 'list', section: 'Quản trị', roles: ['SUPER_ADMIN'] },
    { path: '/admin/system', label: 'Hệ thống', icon: 'database', section: 'Quản trị', roles: ['SUPER_ADMIN'] },
    { path: '/admin/audit', label: 'Nhật ký', icon: 'log', section: 'Quản trị', roles: ['SUPER_ADMIN'] },
    { path: '/me', label: 'Hồ sơ', icon: 'user', section: 'Cá nhân', primary: true },
    { path: '/me/password', label: 'Đổi mật khẩu', icon: 'key', section: 'Cá nhân' },
];

function visibleItems() {
    const me = currentUser();
    return NAV_ITEMS.filter((item) => hasRole(item.roles || null) && (!item.show || item.show(me)));
}

function covers(item, path) {
    return item.path === '/' ? path === '/' : path === item.path || path.startsWith(item.path + '/');
}

/** Chỉ mục khớp dài nhất được đánh dấu: ở /me/password là "Đổi mật khẩu", không phải cả "Hồ sơ" (/me). */
function isActive(item, path) {
    return covers(item, path) && !NAV_ITEMS.some((o) => o.path.length > item.path.length && covers(o, path));
}

function link(item, path, className, text = item.label) {
    return h('a', { href: '#' + item.path, class: className, 'aria-current': isActive(item, path) ? 'page' : null,
        title: item.label },
    icon(item.icon), h('span', { class: 'nav-label' }, text));
}

/** Chữ viết tắt cho ảnh đại diện: chữ đầu của họ và của tên ("Nguyễn Văn An" thành "NA"). */
function initials(name) {
    const words = String(name || '').trim().split(/\s+/).filter(Boolean);
    if (words.length === 0) return '?';
    const first = words[0][0];
    const last = words.length > 1 ? words[words.length - 1][0] : '';
    return (first + last).toUpperCase();
}

// ---------------------------------------------------------------- thu gọn thanh bên (từ 1024px)

function readCollapsed() {
    try {
        return localStorage.getItem(COLLAPSE_KEY) === '1';
    } catch {
        return false;
    }
}

function setCollapsed(collapsed, toggle) {
    document.getElementById('app')?.classList.toggle('nav-collapsed', collapsed);
    toggle.setAttribute('aria-expanded', String(!collapsed));
    toggle.setAttribute('aria-label', collapsed ? 'Mở rộng menu' : 'Thu gọn menu');
    try {
        localStorage.setItem(COLLAPSE_KEY, collapsed ? '1' : '0');
    } catch {
        // Trình duyệt chặn lưu trữ: chỉ không nhớ được lựa chọn.
    }
}

// ---------------------------------------------------------------- thanh trên

function userMenu(me) {
    const name = me?.displayName || '';
    const trigger = h('button', { type: 'button', class: 'user-menu-btn', 'aria-label': `Tài khoản: ${name}` },
        h('span', { class: 'avatar', 'aria-hidden': 'true' }, initials(name)),
        h('span', { class: 'user-meta' },
            h('span', { class: 'user-name' }, name),
            h('span', { class: 'user-role' }, roleLabel(me))),
        icon('chevron-down', { small: true }));
    const panel = h('div', { class: 'menu-panel-user', role: 'menu', 'aria-label': 'Tài khoản' },
        h('div', { class: 'menu-profile' },
            h('span', { class: 'avatar avatar-lg', 'aria-hidden': 'true' }, initials(name)),
            h('span', { class: 'menu-profile-text' },
                h('span', { class: 'menu-title' }, name),
                h('span', { class: 'text-muted' }, me?.unit ? `${roleLabel(me)} · ${me.unit.name}` : roleLabel(me)))),
        h('ul', { class: 'menu-list' },
            h('li', null, h('a', { class: 'menu-item', href: '#/me', role: 'menuitem' }, icon('user', { small: true }), 'Hồ sơ của tôi')),
            h('li', null, h('a', { class: 'menu-item', href: '#/me/password', role: 'menuitem' }, icon('key', { small: true }), 'Đổi mật khẩu')),
            h('li', null, h('button', { type: 'button', class: 'menu-item header-logout', role: 'menuitem', onclick: () => logout() },
                icon('logout', { small: true }), 'Đăng xuất'))));
    return createPopover({ trigger, panel, className: 'user-menu' }).element;
}

export function renderHeader(header) {
    const me = currentUser();
    const toggle = h('button', { type: 'button', class: 'nav-toggle', 'aria-controls': 'app-nav' }, icon('more'));
    toggle.addEventListener('click', () =>
        setCollapsed(!document.getElementById('app')?.classList.contains('nav-collapsed'), toggle));
    setCollapsed(readCollapsed(), toggle);
    replace(header,
        toggle,
        h('a', { class: 'brand', href: '#/' },
            h('img', { src: 'assets/logo.svg', alt: '', width: 32, height: 32 }),
            h('span', { class: 'brand-name' }, 'Quản lý huấn luyện')),
        h('nav', { class: 'header-crumbs', id: 'header-title', 'aria-label': 'Vị trí' }),
        h('div', { class: 'header-spacer' }),
        createBell(),
        userMenu(me));
}

/** Vẽ lại điều hướng theo trang hiện tại (gọi mỗi lần đổi trang). */
export function renderNav(sideNav, tabbar, path) {
    const items = visibleItems();

    const groups = [];
    for (const item of items) {
        const last = groups[groups.length - 1];
        if (last && last.section === item.section) last.items.push(item);
        else groups.push({ section: item.section, items: [item] });
    }
    replace(sideNav,
        h('a', { class: 'nav-brand', href: '#/' },
            h('img', { src: 'assets/logo.svg', alt: '', width: 36, height: 36 }),
            h('span', { class: 'nav-brand-text' },
                h('span', { class: 'brand-name' }, 'QUẢN LÝ HUẤN LUYỆN'),
                h('span', { class: 'brand-sub' }, 'Lữ đoàn 162'))),
        groups.map((g) => h('div', { class: 'nav-group' },
            h('div', { class: 'nav-section' }, g.section),
            h('ul', null, g.items.map((item) => h('li', null, link(item, path)))))));

    const active = items.find((i) => isActive(i, path));
    const crumbs = document.getElementById('header-title');
    if (crumbs) {
        replace(crumbs, active ? [h('span', { class: 'crumb-section' }, active.section), h('span', { class: 'crumb-sep', 'aria-hidden': 'true' }, '/'),
            h('span', { class: 'crumb-page' }, active.label)] : null);
    }

    const primary = items.filter((i) => i.primary);
    const inTabs = primary.slice(0, TAB_SLOTS - 1);
    const inMore = items.filter((i) => !inTabs.includes(i));
    const moreActive = inMore.some((i) => isActive(i, path));

    replace(tabbar,
        inTabs.map((item) => link(item, path, undefined, item.short || item.label)),
        h('button', {
            type: 'button',
            'aria-current': moreActive ? 'page' : null,
            'aria-haspopup': 'dialog',
            onclick: () => openMore(inMore, path),
        }, icon('more'), h('span', { class: 'nav-label' }, 'Thêm')));
}

function openMore(items, path) {
    let modal;
    const list = h('ul', { class: 'more-list' },
        items.map((item) => h('li', null, h('a', {
            href: '#' + item.path,
            'aria-current': isActive(item, path) ? 'page' : null,
            onclick: () => modal.close(),
        }, icon(item.icon), item.label))),
        h('li', null, h('button', { type: 'button', onclick: () => logout() }, icon('logout'), 'Đăng xuất')));
    modal = openModal({ title: 'Menu', body: list });
}
