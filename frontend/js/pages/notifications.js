import { api } from '../core/api.js';
import { formatDateTime } from '../core/format.js';
import { navigate } from '../core/router.js';
import { notificationTarget, refreshUnread } from '../ui/bell.js';
import { h, replace } from '../ui/escape.js';
import { pageHead } from '../ui/page-head.js';
import { createPagination } from '../ui/pagination.js';
import { toast } from '../ui/toast.js';

/** Thông báo của chính mình. Bấm vào một thông báo thì đánh dấu đã đọc và mở đề nghị tương ứng. */

const PAGE_SIZE = 20;


export async function render(container) {
    const state = { page: 0, unreadOnly: false };
    const list = h('ul', { class: 'notice-list' });
    const pagination = createPagination((page) => { state.page = page; load(); });

    const filterBtn = (label, unreadOnly) => h('button', {
        type: 'button',
        class: 'btn btn-secondary',
        'aria-pressed': String(state.unreadOnly === unreadOnly),
        onclick: (e) => {
            state.unreadOnly = unreadOnly;
            state.page = 0;
            for (const b of filters.children) b.setAttribute('aria-pressed', String(b === e.currentTarget));
            load();
        },
    }, label);
    const filters = h('div', { class: 'segmented', role: 'group', 'aria-label': 'Lọc thông báo' });
    replace(filters, filterBtn('Tất cả', false), filterBtn('Chưa đọc', true));

    const readAll = h('button', { type: 'button', class: 'btn btn-secondary', onclick: async () => {
        try {
            const res = await api('/notifications/read-all', { method: 'POST' });
            toast(res.count > 0 ? `Đã đánh dấu ${res.count} thông báo là đã đọc.` : 'Không có thông báo chưa đọc.',
                { type: 'success' });
            await refreshUnread();
            await load();
        } catch (e) {
            toast(e.message, { type: 'error' });
        }
    } }, 'Đánh dấu tất cả đã đọc');

    async function open(n) {
        try {
            if (!n.readAt) {
                await api(`/notifications/${n.id}/read`, { method: 'POST' });
                refreshUnread();
            }
        } catch (e) {
            toast(e.message, { type: 'error' });
            return;
        }
        const target = notificationTarget(n);
        if (target) navigate(target);
        else load();
    }

    function item(n) {
        const unread = !n.readAt;
        return h('li', null, h('button', {
            type: 'button',
            class: ['notice', unread ? 'notice-unread' : null],
            onclick: () => open(n),
        },
        h('span', { class: 'notice-head' },
            unread ? h('span', { class: 'badge badge-warning' }, 'Mới') : null,
            h('span', { class: 'notice-title' }, n.title)),
        n.body ? h('span', { class: 'notice-body' }, n.body) : null,
        h('span', { class: 'notice-time text-muted' }, formatDateTime(n.createdAt))));
    }

    async function load() {
        replace(list, h('li', { class: 'loading' }, 'Đang tải...'));
        try {
            const res = await api('/notifications', { query: { page: state.page, size: PAGE_SIZE, unread: state.unreadOnly || null } });
            if (res.items.length === 0) {
                replace(list, h('li', { class: 'notice-empty text-muted' },
                    state.unreadOnly ? 'Không có thông báo chưa đọc.' : 'Chưa có thông báo nào.'));
            } else {
                replace(list, res.items.map(item));
            }
            pagination.update({ page: res.page, size: res.size, total: res.total });
        } catch (e) {
            replace(list, h('li', { class: 'notice-empty' }, e.message || 'Không tải được thông báo.'));
        }
    }

    replace(container,
        pageHead('Thông báo', null, readAll),
        filters,
        h('section', { class: 'card' }, list),
        pagination.element);
    await load();
}
