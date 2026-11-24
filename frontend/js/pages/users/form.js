import { api } from '../../core/api.js';
import { currentUser } from '../../core/auth.js';
import { catalogOptions, categoryOptions, DEFAULT_PASSWORD, loadCatalogs, loadUnits, unitChoices } from '../../core/refdata.js';
import { navigate } from '../../core/router.js';
import { backLink } from '../../ui/back-link.js';
import { append, h } from '../../ui/escape.js';
import { createForm } from '../../ui/form.js';
import { pageHead } from '../../ui/page-head.js';
import { toast } from '../../ui/toast.js';

/**
 * Tạo mới (#/users/new) hoặc sửa (#/users/:id/edit) người dùng.
 * Khi sửa, đơn vị, tài khoản, quyền, trạng thái, mật khẩu đổi ở trang chi tiết (thao tác riêng, có kiểm tra riêng).
 */
export async function render(container, params) {
    const editing = params.id !== undefined;
    const [cat, units, user] = await Promise.all([
        loadCatalogs(),
        loadUnits(),
        editing ? api(`/users/${params.id}`) : Promise.resolve(null),
    ]);
    const me = currentUser();

    // Từ 1024 px form xếp lưới 4 cột để cả form và nút Lưu nằm trọn trong một màn (2026-11-22); điện thoại một cột.
    const f = {
        lastName: { name: 'lastName', label: 'Họ đệm', required: true, maxLength: 100, autocomplete: 'off', value: user?.lastName },
        firstName: { name: 'firstName', label: 'Tên', required: true, maxLength: 50, autocomplete: 'off', value: user?.firstName },
        personalCode: { name: 'personalCode', label: 'Mã định danh', required: editing, maxLength: 50, autocomplete: 'off',
            hint: editing ? 'Mã duy nhất, dùng để khớp khi nhập Excel.' : 'Để trống để tự cấp dạng ND000001.',
            value: user?.personalCode },
        birthDate: { name: 'birthDate', label: 'Ngày sinh', type: 'date', value: user?.birthDate },
        unitId: { name: 'unitId', label: 'Đơn vị', type: 'radio-select', required: true, placeholder: 'Chọn đơn vị',
            itemLabel: 'đơn vị', requiredMessage: 'Chọn đơn vị.', options: unitChoices(units, { onlyActive: true, onlyMine: true }) },
        // Đối tượng bắt buộc với mọi người có đơn vị; Super Admin (không thuộc đơn vị) được để trống.
        category: { name: 'category', label: 'Đối tượng', type: 'select', required: !(editing && user.role === 'SUPER_ADMIN'),
            placeholder: 'Chọn đối tượng', options: categoryOptions(), value: user?.category || '', requiredMessage: 'Chọn đối tượng.' },
        rankId: { name: 'rankId', label: 'Cấp bậc', type: 'select', placeholder: 'Chưa có',
            options: catalogOptions(cat.ranks, { currentId: user?.rank?.id, withSymbol: true }), value: user?.rank ? String(user.rank.id) : '' },
        positionId: { name: 'positionId', label: 'Chức vụ', type: 'select', placeholder: 'Chưa có',
            options: catalogOptions(cat.positions, { currentId: user?.position?.id }), value: user?.position ? String(user.position.id) : '' },
        phone: { name: 'phone', label: 'Số điện thoại', type: 'tel', inputmode: 'tel', maxLength: 20, autocomplete: 'off', value: user?.phone },
        // Danh sách ô đánh dấu hiện sẵn như cũ (người dùng chọn 2026-11-22), xếp nhiều cột để form vừa một màn.
        specialtyIds: { name: 'specialtyIds', label: 'Chuyên ngành', type: 'checkboxes', full: true,
            options: catalogOptions(cat.specialties, { currentId: null }).concat(
                (user?.specialties || []).filter((s) => !cat.specialties.find((c) => c.id === s.id && c.active))
                    .map((s) => ({ value: String(s.id), label: s.name + ' (đã khóa)' }))),
            value: (user?.specialties || []).map((s) => String(s.id)) },
        // Trang sửa: Ghi chú chiếm cả hàng; trang thêm: nằm ở ô cuối hàng Tài khoản, Quyền để form vừa một màn.
        note: { name: 'note', label: 'Ghi chú', type: 'textarea', full: editing, maxLength: 500, value: user?.note },
        username: { name: 'username', label: 'Tài khoản đăng nhập', maxLength: 50, autocomplete: 'off',
            hint: `Để trống nếu không cần đăng nhập. Mật khẩu ban đầu: ${DEFAULT_PASSWORD}, đổi ở lần đăng nhập đầu.` },
        role: { name: 'role', label: 'Quyền', type: 'select', value: 'USER', options: [
            { value: 'USER', label: 'Người dùng' },
            { value: 'ADMIN', label: 'Admin (quản lý đơn vị và cấp dưới)' }],
        validate: (v, all) => (v !== 'USER' && !all.username ? 'Admin bắt buộc có tài khoản.' : null) },
    };
    // Thứ tự theo hàng của lưới 4 cột.
    const order = editing
        ? ['lastName', 'firstName', 'personalCode', 'birthDate', 'category', 'rankId', 'positionId', 'phone', 'specialtyIds', 'note']
        : ['lastName', 'firstName', 'personalCode', 'birthDate', 'unitId', 'category', 'rankId', 'positionId', 'phone',
            'username', 'role', 'note', 'specialtyIds'];
    const fields = order.map((k) => f[k]);

    const toNumber = (v) => (v === '' || v === null || v === undefined ? null : Number(v));
    const form = createForm({
        columns: 4,
        fields,
        submitLabel: editing ? 'Lưu thay đổi' : 'Tạo người dùng',
        extraActions: [h('a', { class: 'btn btn-secondary', href: editing ? `#/users/${params.id}` : '#/users' }, 'Hủy')],
        onSubmit: async (v) => {
            const body = {
                lastName: v.lastName, firstName: v.firstName, personalCode: v.personalCode,
                rankId: toNumber(v.rankId), positionId: toNumber(v.positionId),
                specialtyIds: v.specialtyIds.map(Number), birthDate: v.birthDate || null,
                phone: v.phone || null, note: v.note || null, category: v.category || null,
            };
            let saved;
            if (editing) {
                saved = await api(`/users/${params.id}`, { method: 'PUT', body: { ...body, version: user.version } });
                toast('Đã lưu thay đổi.', { type: 'success' });
            } else {
                saved = await api('/users', { method: 'POST', body: {
                    ...body, unitId: toNumber(v.unitId), username: v.username || null, role: v.role } });
                toast('Đã tạo người dùng.', { type: 'success' });
            }
            navigate(`/users/${saved.id}`);
        },
    });

    // append() của escape.js bỏ qua null; append() gốc của DOM sẽ in chữ "null".
    append(container, [
        editing ? backLink(`#/users/${params.id}`, 'Thông tin người dùng') : backLink('#/users', 'Người dùng'),
        pageHead(editing ? 'Sửa người dùng' : 'Thêm người dùng', editing ? user.fullName : null),
        me.role === 'ADMIN' && !editing
            ? h('div', { class: 'alert alert-info' }, h('p', null,
                `Chỉ chọn được đơn vị ${me.unit.name} và các đơn vị cấp dưới.`))
            : null,
        h('section', { class: 'card' }, form.element)]);
    form.focusFirst();
}
