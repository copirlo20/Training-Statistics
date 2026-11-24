import { api } from '../../core/api.js';
import { currentUser } from '../../core/auth.js';
import { CATEGORY, catalogOptions, categoryOptions, inMyScope, loadCatalogs, loadUnits, roleText, STATUS } from '../../core/refdata.js';
import { h } from '../../ui/escape.js';
import { filterField } from '../../ui/filter-field.js';
import { icon } from '../../ui/icons.js';
import { multiSelect } from '../../ui/multi-select.js';
import { pageHead } from '../../ui/page-head.js';
import { rowActions } from '../../ui/row-actions.js';
import { createTable } from '../../ui/table.js';
import { toast } from '../../ui/toast.js';
import { changeStatus, openUserActions } from './actions.js';

/** Danh sách người dùng trong phạm vi quản lý. Máy chủ chỉ trả người trong nhánh của người xem. */
export async function render(container) {
    const [cat, units] = await Promise.all([loadCatalogs(), loadUnits()]);
    const filters = { q: '', unitId: '', rankId: '', positionId: '', specialtyId: '', status: '', role: '', category: '' };
    const me = currentUser();

    // Chuyển đơn vị, đổi quyền, tài khoản, mật khẩu... ngay từ danh sách: lấy bản đầy đủ rồi mở hộp thoại thao tác.
    async function moreActions(r) {
        try {
            const user = await api(`/users/${r.id}`);
            openUserActions(user, { onDone: () => table.reload() });
        } catch (e) {
            toast(e.message, { type: 'error' });
        }
    }

    // Khóa, mở khóa, xóa ngay trên dòng.
    const setStatus = (r, status) => changeStatus(r, status, () => table.reload());

    const table = createTable({
        caption: 'Danh sách người dùng',
        emptyText: 'Không có người dùng nào khớp bộ lọc.',
        columns: [
            // Mã định danh dưới họ tên, tài khoản dưới quyền: ít cột hơn để chữ không phải xuống dòng.
            { key: 'name', label: 'Họ tên', sortable: true, render: (r) => h('a', { class: 'cell-link cell-link-stack', href: `#/users/${r.id}` },
                h('span', { class: 'cell-title' }, r.fullName),
                h('span', { class: 'cell-sub mono' }, r.personalCode || '')) },
            { key: 'rank', label: 'Cấp bậc', sortable: true, render: (r) => [
                r.rankSymbol ? h('span', { class: 'mono', title: r.rankName }, r.rankSymbol) : null,
                r.category ? h('span', { class: 'cell-sub', title: CATEGORY[r.category]?.label }, CATEGORY[r.category]?.short || r.category) : null] },
            { key: 'position', label: 'Chức vụ', priority: 3, render: (r) => r.positionName || '' },
            { key: 'unit', label: 'Đơn vị', sortable: true, render: (r) => r.unitName
                ? [r.unitName, h('span', { class: 'cell-sub mono' }, r.unitCode)] : 'Không thuộc đơn vị' },
            { key: 'role', label: 'Quyền', render: (r) => [roleText(r.role, r.unitCode),
                r.username ? h('span', { class: 'cell-sub mono' }, r.username) : null] },
            { key: 'status', label: 'Trạng thái', sortable: true, render: (r) => h('span', { class: STATUS[r.status]?.badge || 'badge' },
                STATUS[r.status]?.label || r.status) },
            { key: 'actions', label: 'Thao tác', actions: true, render: (r) => rowActions([
                { icon: 'eye', label: 'Xem chi tiết', href: `#/users/${r.id}` },
                { icon: 'edit', label: 'Sửa', href: `#/users/${r.id}/edit` },
                r.id !== me.id && r.status === 'ACTIVE' ? { icon: 'lock', label: 'Khóa', onClick: () => setStatus(r, 'INACTIVE') } : null,
                r.status !== 'ACTIVE' ? { icon: 'unlock', label: r.status === 'DELETED' ? 'Khôi phục' : 'Mở khóa',
                    onClick: () => setStatus(r, 'ACTIVE') } : null,
                r.id !== me.id && r.status !== 'DELETED' ? { icon: 'trash', label: 'Xóa', tone: 'danger',
                    onClick: () => setStatus(r, 'DELETED') } : null,
                { icon: 'more', label: 'Thao tác khác', onClick: () => moreActions(r) }]) },
        ],
        load: ({ page, size, sort, dir }) => api('/users', { query: { ...filters, page, size, sort, dir } }),
    });

    // Mỗi tiêu chí chọn được nhiều giá trị cùng lúc (ô thả xuống có ô đánh dấu); không chọn gì là không lọc.
    const apply = (e) => {
        e?.preventDefault();
        const list = (sel) => sel.values().join(',');
        Object.assign(filters, {
            q: q.value.trim(), unitId: list(unitSel), rankId: list(rankSel), positionId: list(posSel),
            specialtyId: list(specSel), status: list(statusSel), role: list(roleSel), category: list(catSel),
        });
        table.reload(true);
    };
    const pick = (id, options, allLabel, unit, searchable = true) => multiSelect({ id, options, allLabel, unit, searchable,
        onChange: () => apply() });
    const catalog = (items, withSymbol = false) => catalogOptions(items, { withSymbol }).map((o) => ({ value: o.value, label: o.label }));

    const q = h('input', { id: 'u-q', class: 'input', type: 'search', placeholder: 'Họ tên, mã định danh, tài khoản', autocomplete: 'off' });
    const mine = units.filter((u) => inMyScope(u.code));
    const unitSel = pick('u-unit', mine.map((u) => ({ value: String(u.id), label: u.name, sub: u.code,
        indent: u.level > 1 && mine.some((p) => p.id === u.parentId) })), 'Tất cả đơn vị', 'đơn vị');
    const rankSel = pick('u-rank', catalog(cat.ranks, true), 'Tất cả cấp bậc', 'cấp bậc');
    const posSel = pick('u-pos', catalog(cat.positions), 'Tất cả chức vụ', 'chức vụ');
    const catSel = pick('u-cat', categoryOptions(), 'Mọi đối tượng', 'đối tượng', false);
    const specSel = pick('u-spec', catalog(cat.specialties), 'Tất cả chuyên ngành', 'chuyên ngành');
    const statusSel = pick('u-status', [
        { value: 'ACTIVE', label: 'Hoạt động' }, { value: 'INACTIVE', label: 'Đã khóa' }, { value: 'DELETED', label: 'Đã xóa' }],
    'Đang dùng (chưa xóa)', 'trạng thái', false);
    const roleSel = pick('u-role', [
        { value: 'USER', label: 'Người dùng' }, { value: 'ADMIN', label: 'Admin' }, { value: 'SUPER_ADMIN', label: 'Super Admin' }],
    'Tất cả quyền', 'quyền', false);
    let timer;
    q.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(() => apply(), 300); });

    const reset = h('button', { type: 'button', class: 'btn btn-secondary', onclick: () => {
        q.value = '';
        for (const s of [unitSel, catSel, rankSel, posSel, specSel, statusSel, roleSel]) s.setValues([]);
        apply();
    } }, 'Bỏ lọc');

    // Bộ lọc mở sẵn trên máy tính, thu gọn trên điện thoại. Từ 1024 px xếp đúng hai hàng (lưới 5 cột đều):
    // hàng 1 tìm, đơn vị, đối tượng, cấp bậc, chức vụ; hàng 2 chuyên ngành, trạng thái, quyền, Bỏ lọc.
    const details = h('details', { class: 'filters', open: window.matchMedia('(min-width: 600px)').matches },
        h('summary', null, icon('search', { small: true }), 'Tìm kiếm và lọc'),
        h('form', { class: 'toolbar toolbar-dense toolbar-2rows', role: 'search', onsubmit: (e) => { clearTimeout(timer); apply(e); } },
            filterField('u-q', 'Tìm', q),
            filterField('u-unit', 'Đơn vị (kể cả đơn vị con)', unitSel.element),
            filterField('u-cat', 'Đối tượng', catSel.element),
            filterField('u-rank', 'Cấp bậc', rankSel.element),
            filterField('u-pos', 'Chức vụ', posSel.element),
            filterField('u-spec', 'Chuyên ngành', specSel.element),
            filterField('u-status', 'Trạng thái', statusSel.element),
            filterField('u-role', 'Quyền', roleSel.element),
            h('div', { class: 'field field-action' }, reset)));

    container.append(
        pageHead('Người dùng',
            'Người dùng trong phạm vi đơn vị bạn quản lý.',
            [h('a', { class: 'btn btn-secondary', href: '#/users/import' }, icon('log', { small: true }), h('span', null, 'Nhập Excel')),
                h('a', { class: 'btn', href: '#/users/new' }, icon('user', { small: true }), h('span', null, 'Thêm người dùng'))]),
        details,
        table.element);
    table.reload(true);
}
