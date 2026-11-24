import { api } from '../../core/api.js';
import { currentUser } from '../../core/auth.js';
import { formatDateTime } from '../../core/format.js';
import { roleText, STATUS } from '../../core/refdata.js';
import { navigate } from '../../core/router.js';
import { actionMenu } from '../../ui/action-menu.js';
import { backLink } from '../../ui/back-link.js';
import { dlRow } from '../../ui/dl.js';
import { h, replace } from '../../ui/escape.js';
import { icon } from '../../ui/icons.js';
import { pageHead } from '../../ui/page-head.js';
import { openAccount, revokeAccount, userActionItems } from './actions.js';
import { historyWithPrint } from './history.js';
import { personRows } from './person-rows.js';

/** Chi tiết người dùng và các thao tác: sửa, cấp/đổi/thu hồi tài khoản, chuyển đơn vị, đổi quyền, đặt lại mật khẩu, khóa, xóa, in. */
export async function render(container, params) {
    const me = currentUser();
    let user = await api(`/users/${params.id}`);
    const history = await api(`/users/${params.id}/history`);
    const historyPrint = historyWithPrint(params.id, 'In');

    const reload = async () => {
        user = await api(`/users/${params.id}`);
        draw();
    };


    const cb = { onDone: reload, onGone: () => navigate('/users') };

    function actions() {
        if (me.role === 'USER') {
            return [historyPrint.printButton];
        }
        return [
            h('a', { class: 'btn', href: `#/users/${user.id}/edit` }, icon('edit', { small: true }), h('span', null, 'Sửa thông tin')),
            historyPrint.printButton,
            actionMenu('Thao tác khác', userActionItems(user, cb).filter((a) => !a.key)),
        ];
    }

    function accountActions() {
        if (me.role === 'USER') return null;
        if (!user.username) {
            return h('div', { class: 'btn-row card-actions' },
                h('button', { type: 'button', class: 'btn', onclick: () => openAccount(user, reload) }, icon('key', { small: true }), h('span', null, 'Cấp tài khoản')));
        }
        return h('div', { class: 'btn-row card-actions' },
            h('button', { type: 'button', class: 'btn btn-secondary', onclick: () => openAccount(user, reload) }, icon('edit', { small: true }), h('span', null, 'Đổi tài khoản')),
            user.role === 'USER' && user.id !== me.id
                ? h('button', { type: 'button', class: 'btn btn-secondary', onclick: () => revokeAccount(user, reload) }, icon('lock', { small: true }), h('span', null, 'Thu hồi'))
                : null);
    }

    function draw() {
        const st = STATUS[user.status] || { label: user.status, badge: 'badge' };
        replace(container,
            me.role !== 'USER' ? backLink('#/users', 'Người dùng') : null,
            pageHead(user.fullName,
                [h('span', { class: st.badge }, st.label), ' ', roleText(user.role, user.unit?.code)]),
            h('div', { class: 'btn-row page-actions' }, actions()),
            h('div', { class: 'grid-cards' },
                h('section', { class: 'card' },
                    h('h2', null, 'Thông tin cá nhân'),
                    h('dl', { class: 'dl' },
                        ...personRows(user),
                        dlRow('Số điện thoại', user.phone),
                        dlRow('Ghi chú', user.note))),
                h('section', { class: 'card' },
                    h('h2', null, 'Tài khoản'),
                    h('dl', { class: 'dl' },
                        dlRow('Tài khoản', user.username && h('span', { class: 'mono' }, user.username)),
                        dlRow('Mật khẩu', user.username ? (user.hasPassword ? 'Đã đặt' : 'Chưa đặt, chưa đăng nhập được') : null),
                        dlRow('Quyền', roleText(user.role, user.unit?.code)),
                        dlRow('Đăng nhập lần cuối', formatDateTime(user.lastLoginAt)),
                        dlRow('Cập nhật', formatDateTime(user.updatedAt))),
                    accountActions())),
            historyPrint.card('Nội dung kiểm tra đã tham gia và điểm', history, 'Người này chưa tham gia nội dung kiểm tra nào.'));
    }

    draw();
}
