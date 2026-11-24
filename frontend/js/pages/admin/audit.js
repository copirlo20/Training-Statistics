import { api } from '../../core/api.js';
import { formatDateTime } from '../../core/format.js';
import { h } from '../../ui/escape.js';
import { filterField } from '../../ui/filter-field.js';
import { icon } from '../../ui/icons.js';
import { openModal } from '../../ui/modal.js';
import { pageHead } from '../../ui/page-head.js';
import { rowActions } from '../../ui/row-actions.js';
import { createTable } from '../../ui/table.js';

/** Nhật ký thao tác (chỉ Super Admin). Lọc theo người, hành động, ngày; xem chi tiết trước và sau. */

const ACTIONS = {
    LOGIN_SUCCESS: 'Đăng nhập',
    LOGIN_FAILED: 'Đăng nhập thất bại',
    LOGOUT: 'Đăng xuất',
    PASSWORD_CHANGED: 'Đổi mật khẩu',
    SUPER_ADMIN_CREATED: 'Tạo Super Admin',
    SUPER_ADMIN_RESET: 'Đặt lại Super Admin',
};

function actionBadge(action) {
    const failed = action.endsWith('_FAILED');
    return h('span', { class: failed ? 'badge badge-danger' : 'badge' }, ACTIONS[action] || action);
}

function showDetail(row) {
    const block = (title, value) => [
        h('h3', null, title),
        value == null
            ? h('p', { class: 'text-muted' }, 'Không có')
            : h('pre', { class: 'json-view' }, JSON.stringify(value, null, 2)),
    ];
    openModal({
        title: 'Chi tiết thao tác',
        body: [
            h('dl', { class: 'dl' },
                h('dt', null, 'Thời gian'), h('dd', null, formatDateTime(row.at)),
                h('dt', null, 'Người thực hiện'), h('dd', null, row.actorUsername || 'Hệ thống'),
                h('dt', null, 'Hành động'), h('dd', null, ACTIONS[row.action] || row.action, ' ', h('span', { class: 'mono text-muted' }, row.action)),
                h('dt', null, 'Đối tượng'), h('dd', null, row.entityType ? `${row.entityType} #${row.entityId ?? ''}` : 'Không có'),
                h('dt', null, 'Đơn vị'), h('dd', null, row.unitCode || 'Không có'),
                h('dt', null, 'Địa chỉ IP'), h('dd', { class: 'mono' }, row.ip || 'Không có')),
            block('Trước', row.before),
            block('Sau', row.after),
        ],
        actions: [{ label: 'Đóng', variant: 'secondary' }],
    });
}

export function render(container) {
    const filters = { actor: '', action: '', from: '', to: '' };

    const table = createTable({
        caption: 'Nhật ký thao tác',
        emptyText: 'Không có bản ghi nào khớp bộ lọc.',
        columns: [
            { key: 'at', label: 'Thời gian', render: (r) => h('span', { class: 'nowrap' }, formatDateTime(r.at)) },
            { key: 'actorUsername', label: 'Người thực hiện', render: (r) => r.actorUsername || 'Hệ thống' },
            { key: 'action', label: 'Hành động', render: (r) => actionBadge(r.action) },
            { key: 'unitCode', label: 'Đơn vị', priority: 3, render: (r) => h('span', { class: 'mono' }, r.unitCode || '') },
            { key: 'ip', label: 'Địa chỉ IP', priority: 3, render: (r) => h('span', { class: 'mono' }, r.ip || '') },
            { key: 'detail', label: 'Chi tiết', actions: true, render: (r) => rowActions([
                { icon: 'eye', label: `Xem chi tiết thao tác lúc ${formatDateTime(r.at)}`, onClick: () => showDetail(r) }]) },
        ],
        load: ({ page, size }) => api('/audit-logs', { query: { ...filters, page, size } }),
    });

    const actorInput = h('input', { id: 'f-actor', class: 'input', type: 'search', autocomplete: 'off', placeholder: 'Tên tài khoản' });
    const actionSelect = h('select', { id: 'f-action', class: 'select' },
        h('option', { value: '' }, 'Tất cả'),
        Object.entries(ACTIONS).map(([v, label]) => h('option', { value: v }, label)));
    const fromInput = h('input', { id: 'f-from', class: 'input', type: 'date' });
    const toInput = h('input', { id: 'f-to', class: 'input', type: 'date' });

    const apply = (e) => {
        e?.preventDefault();
        filters.actor = actorInput.value.trim();
        filters.action = actionSelect.value;
        filters.from = fromInput.value;
        filters.to = toInput.value;
        table.reload(true);
    };
    actionSelect.addEventListener('change', apply);

    const toolbar = h('form', { class: 'toolbar', role: 'search', onsubmit: apply },
        filterField('f-actor', 'Người thực hiện', actorInput),
        filterField('f-action', 'Hành động', actionSelect),
        filterField('f-from', 'Từ ngày', fromInput),
        filterField('f-to', 'Đến ngày', toInput),
        h('button', { type: 'submit', class: 'btn' }, icon('search', { small: true }), h('span', null, 'Lọc')));

    container.append(
        pageHead('Nhật ký thao tác', 'Chỉ xem, không sửa hay xóa được. Thời gian theo giờ Việt Nam.'),
        toolbar,
        table.element);
    table.reload(true);
}
