import { api } from '../core/api.js';
import { currentUser } from '../core/auth.js';
import { formatDateTime } from '../core/format.js';
import { navigate } from '../core/router.js';
import { h, replace } from './escape.js';
import { icon } from './icons.js';
import { createPopover } from './popover.js';
import { toast } from './toast.js';

/**
 * Chuông thông báo trên thanh trên: số chưa đọc, hỏi máy chủ mỗi 30 giây (R18). Bấm vào mở khung xem nhanh các
 * thông báo mới nhất.
 *
 * Mỗi lần hỏi đều làm mới phiên đăng nhập, nên nếu cứ hỏi mãi thì phiên không bao giờ hết hạn khi người dùng bỏ đi
 * (trái R4c). Vì vậy chỉ hỏi khi tab đang hiển thị và người dùng có thao tác trong khoảng thời gian hết phiên.
 */

const POLL_MS = 30000;
const SAFETY_MS = 60000;
const PREVIEW = 5;

let button;
let count;
let lastActivity = Date.now();
let started = false;

/** Trang đích của thông báo theo loại tham chiếu. */
export function notificationTarget(n) {
    if (n.refType !== 'class_request' || !n.refId) return null;
    // Đề nghị mới cần duyệt: mở cả danh sách đang chờ (gom theo nội dung) và đánh dấu nhóm có đề nghị này,
    // vì một thông báo gộp có thể ứng với nhiều đề nghị. Kết quả duyệt, từ chối thì mở đúng đề nghị đó.
    if (n.type === 'REQUEST_CREATED') return `/requests?focus=${n.refId}`;
    return `/requests/${n.refId}`;
    return null;
}

export function createBell() {
    count = h('span', { class: 'bell-count', hidden: true });
    button = h('button', { type: 'button', class: 'header-bell', 'aria-label': 'Thông báo' }, icon('bell'), count);
    const list = h('ul', { class: 'menu-list' });
    const readAll = h('button', { type: 'button', class: 'menu-link' }, 'Đánh dấu đã đọc hết');
    const panel = h('div', { class: 'menu-panel-wide', role: 'region', 'aria-label': 'Thông báo mới' },
        h('div', { class: 'menu-head' }, h('span', { class: 'menu-title' }, 'Thông báo'), readAll),
        list,
        h('a', { class: 'menu-foot', href: '#/notifications' }, 'Xem tất cả thông báo'));

    async function load() {
        replace(list, h('li', { class: 'loading' }, 'Đang tải...'));
        try {
            const res = await api('/notifications', { query: { size: PREVIEW } });
            replace(list, res.items.length === 0
                ? h('li', { class: 'menu-empty text-muted' }, 'Chưa có thông báo nào.')
                : res.items.map((n) => h('li', null, h('button', {
                    type: 'button', class: ['menu-item', 'menu-notice', n.readAt ? null : 'is-unread'],
                    onclick: () => open(n),
                },
                h('span', { class: 'menu-notice-icon', 'aria-hidden': 'true' }, icon('bell', { small: true })),
                h('span', { class: 'menu-notice-text' },
                    h('span', { class: 'menu-notice-title' }, n.title),
                    h('span', { class: 'menu-notice-time' }, formatDateTime(n.createdAt)))))));
        } catch (e) {
            replace(list, h('li', { class: 'menu-empty text-danger' }, e.message));
        }
    }

    async function open(n) {
        popover.close();
        try {
            if (!n.readAt) await api(`/notifications/${n.id}/read`, { method: 'POST' });
        } catch (e) {
            toast(e.message, { type: 'error' });
        }
        refreshUnread();
        navigate(notificationTarget(n) || '/notifications');
    }

    readAll.addEventListener('click', async () => {
        try {
            await api('/notifications/read-all', { method: 'POST' });
            await refreshUnread();
            await load();
        } catch (e) {
            toast(e.message, { type: 'error' });
        }
    });

    const popover = createPopover({ trigger: button, panel, onOpen: load, className: 'bell-menu' });
    if (!started) {
        started = true;
        startPolling();
    }
    refreshUnread();
    return popover.element;
}

/** Hỏi lại ngay số chưa đọc (gọi sau khi đọc thông báo). */
export async function refreshUnread() {
    try {
        const res = await api('/notifications/unread-count');
        show(res?.count || 0);
    } catch {
        // Lỗi mạng tạm thời: giữ số cũ, lần hỏi sau sẽ cập nhật.
    }
}

function show(n) {
    if (!count || !button) return;
    if (n > 0) {
        replace(count, n > 99 ? '99+' : String(n));
        count.hidden = false;
        button.setAttribute('aria-label', `Thông báo, ${n} chưa đọc`);
    } else {
        replace(count);
        count.hidden = true;
        button.setAttribute('aria-label', 'Thông báo');
    }
}

function userIsActive() {
    const minutes = currentUser()?.sessionTimeoutMinutes || 30;
    return Date.now() - lastActivity < minutes * 60000 - SAFETY_MS;
}

function startPolling() {
    const markActive = () => { lastActivity = Date.now(); };
    document.addEventListener('pointerdown', markActive, { passive: true });
    document.addEventListener('keydown', markActive);
    window.addEventListener('hashchange', () => {
        markActive();
        refreshUnread();
    });
    document.addEventListener('visibilitychange', () => {
        if (!document.hidden && userIsActive()) refreshUnread();
    });
    setInterval(() => {
        if (!document.hidden && userIsActive()) refreshUnread();
    }, POLL_MS);
}
