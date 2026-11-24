import { api } from '../../core/api.js';
import { currentUser } from '../../core/auth.js';
import { DEFAULT_PASSWORD, inMyScope, loadUnits, PASSWORD_MIN_LENGTH, unitChoices } from '../../core/refdata.js';
import { h, replace } from '../../ui/escape.js';
import { createForm } from '../../ui/form.js';
import { icon } from '../../ui/icons.js';
import { confirmDialog, openModal } from '../../ui/modal.js';
import { toast } from '../../ui/toast.js';

/**
 * Thao tác trên một người dùng, dùng chung cho trang chi tiết và trang danh sách: chuyển đơn vị, đổi quyền, cấp, đổi,
 * thu hồi tài khoản, đặt lại mật khẩu, khóa, mở khóa, xóa. `user` là bản đầy đủ (GET /users/{id}).
 * Quyền thật do máy chủ kiểm; danh sách chỉ ẩn mục chắc chắn không làm được (tự thao tác lên mình...).
 *
 * @param {object} user
 * @param {{onDone: () => void, onGone?: () => void}} cb onGone: người này đã ra khỏi phạm vi sau khi chuyển đơn vị
 */
export function userActionItems(user, { onDone, onGone = onDone }) {
    const me = currentUser();
    const self = user.id === me.id;
    return [
        !self && user.role !== 'SUPER_ADMIN' ? { label: 'Chuyển đơn vị', icon: 'unit', onClick: () => openMove(user, onDone, onGone) } : null,
        !self ? { label: 'Đổi quyền', icon: 'shield', onClick: () => openRole(user, onDone) } : null,
        { key: 'account', label: user.username ? 'Đổi tài khoản' : 'Cấp tài khoản', icon: 'key', onClick: () => openAccount(user, onDone) },
        user.username && user.role === 'USER' && !self ? { key: 'account', label: 'Thu hồi tài khoản', icon: 'lock', onClick: () => revokeAccount(user, onDone) } : null,
        !self && user.username ? { label: 'Đặt lại mật khẩu', icon: 'key', onClick: () => openReset(user) } : null,
        { key: 'print', label: 'In thông tin', icon: 'log', href: `#/users/${user.id}/print` },
        !self && user.status === 'ACTIVE' ? { label: 'Khóa tài khoản', icon: 'lock', onClick: () => changeStatus(user, 'INACTIVE', onDone) } : null,
        user.status !== 'ACTIVE' ? { label: user.status === 'DELETED' ? 'Khôi phục' : 'Mở khóa', icon: 'unlock',
            onClick: () => changeStatus(user, 'ACTIVE', onDone) } : null,
        !self && user.status !== 'DELETED' ? { label: 'Xóa', icon: 'trash', tone: 'danger', onClick: () => changeStatus(user, 'DELETED', onDone) } : null,
    ].filter(Boolean);
}

/** Hộp thoại liệt kê các thao tác (dùng từ nút "Thao tác khác" trên dòng của bảng: không bị khung cuộn của bảng cắt). */
export function openUserActions(user, cb) {
    let modal;
    const items = userActionItems(user, cb);
    const list = h('ul', { class: 'more-list' }, items.map((a) => h('li', null, a.href
        ? h('a', { href: a.href, class: a.tone === 'danger' ? 'menu-item-danger' : null, onclick: () => modal.close() }, icon(a.icon), a.label)
        : h('button', { type: 'button', class: a.tone === 'danger' ? 'menu-item-danger' : null,
            onclick: () => { modal.close(); a.onClick(); } }, icon(a.icon), a.label))));
    modal = openModal({ title: user.fullName, body: [
        h('p', { class: 'cell-sub' }, [user.personalCode, user.unit?.path].filter(Boolean).join(' · ')), list] });
}

/** Khóa, mở khóa, xóa, khôi phục (sau khi xác nhận). user cần id, fullName, status; máy chủ kiểm phạm vi và tự thao tác. */
export async function changeStatus(user, status, onDone) {
    const text = {
        INACTIVE: ['Khóa tài khoản', `Khóa ${user.fullName}? Người này sẽ bị đăng xuất và không đăng nhập được.`, false],
        DELETED: ['Xóa người dùng', `Xóa ${user.fullName}? Bản ghi vẫn được giữ cho thống kê và có thể khôi phục.`, true],
        ACTIVE: [user.status === 'DELETED' ? 'Khôi phục' : 'Mở khóa', `Cho ${user.fullName} hoạt động trở lại?`, false],
    }[status];
    if (!(await confirmDialog(text[1], { title: text[0], okLabel: text[0], danger: text[2] }))) return;
    try {
        await api(`/users/${user.id}/status`, { method: 'PATCH', body: { status } });
        toast('Đã cập nhật trạng thái.', { type: 'success' });
        await onDone();
    } catch (e) {
        toast(e.message, { type: 'error' });
    }
}

async function openMove(user, onDone, onGone) {
    const me = currentUser();
    const units = await loadUnits();
    const warn = h('div', { class: 'alert alert-warning', hidden: true });
    function showWarning(value) {
        const dest = units.find((u) => String(u.id) === value);
        if (!dest || inMyScope(dest.code)) {
            warn.hidden = true;
            return;
        }
        replace(warn, h('p', null, 'Đơn vị này nằm ngoài phạm vi quản lý của bạn: sau khi chuyển, bạn sẽ không còn xem hay sửa được người này.',
            user.role === 'ADMIN' && me.role !== 'SUPER_ADMIN' ? ' Người này cũng sẽ bị hạ từ Admin xuống Người dùng.' : ''));
        warn.hidden = false;
    }
    let modal;
    const form = createForm({
        fields: [{ name: 'unitId', label: 'Đơn vị chuyển đến', type: 'radio-select', inline: true, required: true,
            itemLabel: 'đơn vị', requiredMessage: 'Chọn đơn vị chuyển đến.', onChange: showWarning,
            options: unitChoices(units, { onlyActive: true }).filter((o) => o.value !== String(user.unit?.id)) }],
        submitLabel: 'Chuyển đơn vị',
        onSubmit: async (v) => {
            const res = await api(`/users/${user.id}/move`, { method: 'POST', body: { unitId: Number(v.unitId) } });
            modal.close();
            if (res.downgraded) toast('Đã chuyển. Người này bị hạ từ Admin xuống Người dùng.', { type: 'success' });
            else toast('Đã chuyển đơn vị.', { type: 'success' });
            if (!res.stillInScope) await onGone();
            else await onDone();
        },
    });
    modal = openModal({ title: 'Chuyển đơn vị', body: [
        h('p', null, `${user.fullName} đang ở `, h('strong', null, user.unit?.path || ''), '.'), warn, form.element] });
}

function openRole(user, onDone) {
    const me = currentUser();
    const options = [{ value: 'USER', label: 'Người dùng' }, { value: 'ADMIN', label: 'Admin (theo cấp đơn vị)' }];
    if (me.role === 'SUPER_ADMIN') options.push({ value: 'SUPER_ADMIN', label: 'Super Admin' });
    const needUnit = user.role === 'SUPER_ADMIN';
    let modal;
    const run = (units) => {
        const fields = [{ name: 'role', label: 'Quyền mới', type: 'select', required: true, value: user.role, options }];
        if (needUnit) {
            fields.push({ name: 'unitId', label: 'Đơn vị (khi bỏ quyền Super Admin)', type: 'radio-select', inline: true,
                itemLabel: 'đơn vị', options: unitChoices(units, { onlyActive: true }),
                validate: (v, all) => (all.role !== 'SUPER_ADMIN' && !v ? 'Chọn đơn vị.' : null) });
        }
        const form = createForm({
            fields,
            submitLabel: 'Đổi quyền',
            onSubmit: async (v) => {
                await api(`/users/${user.id}/role`, { method: 'PUT', body: { role: v.role, unitId: v.unitId ? Number(v.unitId) : null } });
                modal.close();
                toast('Đã đổi quyền.', { type: 'success' });
                await onDone();
            },
        });
        modal = openModal({ title: `Đổi quyền: ${user.fullName}`, body: [
            user.username ? null : h('div', { class: 'alert alert-warning' },
                h('p', null, 'Người này chưa có tài khoản: chỉ giữ được quyền Người dùng. Cấp tài khoản trước.')),
            form.element] });
    };
    if (needUnit) loadUnits().then(run);
    else run([]);
}

export function openAccount(user, onDone) {
    const granting = !user.username;
    let modal;
    const form = createForm({
        fields: [{ name: 'username', label: 'Tài khoản đăng nhập', required: true, maxLength: 50, autocomplete: 'off',
            value: user.username, requiredMessage: 'Nhập tài khoản.',
            hint: 'Từ 3 đến 50 ký tự: chữ không dấu, số, dấu chấm, gạch dưới, gạch ngang.' + (granting ? '' : ' Mật khẩu giữ nguyên.') }],
        submitLabel: granting ? 'Cấp tài khoản' : 'Đổi tài khoản',
        onSubmit: async (v) => {
            const saved = await api(`/users/${user.id}/account`, { method: 'PUT', body: { username: v.username } });
            modal.close();
            toast(granting ? `Đã cấp tài khoản ${saved.username}. Mật khẩu ban đầu: ${DEFAULT_PASSWORD}.`
                : `Đã đổi tài khoản thành ${saved.username}.`, { type: 'success' });
            await onDone();
        },
    });
    modal = openModal({ title: granting ? 'Cấp tài khoản' : 'Đổi tài khoản', body: [
        h('p', null, user.fullName),
        granting ? h('div', { class: 'alert alert-info' }, h('p', null, 'Mật khẩu ban đầu là ',
            h('strong', { class: 'mono' }, DEFAULT_PASSWORD), '. Lần đăng nhập đầu, người dùng phải đặt mật khẩu mới.')) : null,
        form.element] });
}

export async function revokeAccount(user, onDone) {
    const ok = await confirmDialog(`Thu hồi tài khoản ${user.username} của ${user.fullName}? Người này sẽ không đăng nhập được nữa, mật khẩu bị xóa. Hồ sơ và điểm vẫn giữ nguyên.`,
        { title: 'Thu hồi tài khoản', okLabel: 'Thu hồi', danger: true });
    if (!ok) return;
    try {
        await api(`/users/${user.id}/account`, { method: 'PUT', body: { username: null } });
        toast('Đã thu hồi tài khoản.', { type: 'success' });
        await onDone();
    } catch (e) {
        toast(e.message, { type: 'error' });
    }
}

function openReset(user) {
    let modal;
    const form = createForm({
        fields: [
            { name: 'newPassword', label: 'Mật khẩu mới', type: 'password', required: true, minLength: PASSWORD_MIN_LENGTH,
                autocomplete: 'new-password', requiredMessage: 'Nhập mật khẩu mới.' },
            { name: 'confirm', label: 'Nhập lại', type: 'password', required: true, autocomplete: 'new-password',
                requiredMessage: 'Nhập lại mật khẩu mới.',
                validate: (v, all) => (v !== all.newPassword ? 'Hai lần nhập không khớp.' : null) },
        ],
        submitLabel: 'Đặt lại mật khẩu',
        onSubmit: async (v) => {
            await api(`/users/${user.id}/reset-password`, { method: 'POST', body: { newPassword: v.newPassword } });
            modal.close();
            toast('Đã đặt lại mật khẩu. Người dùng đăng nhập bằng mật khẩu này rồi phải đặt mật khẩu mới.', { type: 'success' });
        },
    });
    modal = openModal({ title: `Đặt lại mật khẩu cho ${user.username}`, body: form.element });
}
