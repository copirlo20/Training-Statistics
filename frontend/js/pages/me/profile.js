import { api } from '../../core/api.js';
import { currentUser, roleLabel } from '../../core/auth.js';
import { dlRow } from '../../ui/dl.js';
import { h, replace } from '../../ui/escape.js';
import { icon } from '../../ui/icons.js';
import { pageHead } from '../../ui/page-head.js';
import { historyWithPrint } from '../users/history.js';
import { personRows } from '../users/person-rows.js';

/** Hồ sơ của tôi: thông tin cá nhân, các lớp đã tham gia và điểm của mình (không có điểm của đơn vị, Q5). */
export async function render(container) {
    const me = currentUser();
    const [user, classes] = await Promise.all([api(`/users/${me.id}`), api('/me/classes')]);
    const historyPrint = historyWithPrint(me.id, 'In thông tin cá nhân');
    replace(container,
        pageHead('Hồ sơ của tôi', `${user.fullName} · ${roleLabel(me)}`),
        h('div', { class: 'btn-row page-actions' },
            historyPrint.printButton,
            h('a', { class: 'btn btn-secondary', href: '#/me/password' }, icon('key', { small: true }), 'Đổi mật khẩu')),
        h('section', { class: 'card' },
            h('h2', null, 'Thông tin cá nhân'),
            h('dl', { class: 'dl' },
                ...personRows(user),
                dlRow('Tài khoản', user.username && h('span', { class: 'mono' }, user.username)))),
        historyPrint.card('Nội dung kiểm tra đã tham gia và điểm', classes, 'Bạn chưa tham gia nội dung kiểm tra nào.'));
}
