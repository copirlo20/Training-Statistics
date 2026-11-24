import { api } from '../../core/api.js';
import { loadUnits } from '../../core/refdata.js';
import { h, replace } from '../../ui/escape.js';
import { createForm } from '../../ui/form.js';
import { confirmDialog, openModal } from '../../ui/modal.js';
import { pageHead } from '../../ui/page-head.js';
import { rowActions } from '../../ui/row-actions.js';
import { toast } from '../../ui/toast.js';

/** Quản lý cây đơn vị (Super Admin). Mã, cấp, đơn vị cha không đổi được sau khi tạo. */
export async function render(container) {
    const list = h('div');
    container.append(
        pageHead('Đơn vị',
            'Tối đa 2 cấp. Mã và đơn vị cha không đổi được sau khi tạo, chỉ đổi được tên.',
            h('button', { type: 'button', class: 'btn', onclick: () => openEdit(null, null) }, 'Thêm đơn vị cấp 1')),
        list);

    async function draw(force) {
        const units = await loadUnits(force);
        const roots = units.filter((u) => u.level === 1);
        replace(list, roots.map((root) => h('section', { class: 'card' },
            unitRow(root, true),
            h('ul', { class: 'unit-children' },
                units.filter((u) => u.parentId === root.id).map((c) => h('li', null, unitRow(c, false)))))));
    }

    function unitRow(u, isRoot) {
        return h('div', { class: 'unit-row' },
            h('div', { class: 'unit-name' },
                isRoot ? h('h2', { class: 'visually-hidden' }, u.name) : null,
                h('strong', null, u.name), ' ',
                h('span', { class: 'mono text-muted' }, u.code), ' ',
                u.active ? null : h('span', { class: 'badge badge-warning' }, 'Đã khóa')),
            rowActions([
                isRoot ? { icon: 'plus', label: 'Thêm đơn vị con', onClick: () => openEdit(null, u) } : null,
                { icon: 'edit', label: 'Đổi tên', onClick: () => openEdit(u, null) },
                { icon: u.active ? 'lock' : 'unlock', label: u.active ? 'Khóa' : 'Mở khóa', onClick: () => toggle(u) }]));
    }

    async function toggle(u) {
        const msg = u.active
            ? `Khóa ${u.name}? Không chọn được đơn vị này khi tạo hay chuyển người dùng. Người đang thuộc đơn vị không bị ảnh hưởng.`
            : `Mở khóa ${u.name}?`;
        if (!(await confirmDialog(msg, { title: u.active ? 'Khóa đơn vị' : 'Mở khóa đơn vị', okLabel: u.active ? 'Khóa' : 'Mở khóa' }))) return;
        try {
            await api(`/units/${u.id}/status`, { method: 'PATCH', body: { active: !u.active } });
            toast('Đã cập nhật.', { type: 'success' });
            await draw(true);
        } catch (e) {
            toast(e.message, { type: 'error' });
        }
    }

    /** unit != null: đổi tên. parent != null: thêm đơn vị con. Cả hai null: thêm đơn vị cấp 1. */
    function openEdit(unit, parent) {
        let modal;
        const fields = [{ name: 'name', label: 'Tên đơn vị', required: true, maxLength: 100, value: unit?.name,
            requiredMessage: 'Nhập tên đơn vị.' }];
        if (!unit) {
            fields.push({ name: 'segment', label: 'Mã (4 ký tự)', maxLength: 4, autocomplete: 'off',
                hint: `Để trống để tự đánh số. Mã đầy đủ: ${parent ? parent.code + '.xxxx' : 'xxxx'}. Không đổi được sau khi tạo.` });
        }
        const form = createForm({
            fields,
            submitLabel: unit ? 'Lưu' : 'Tạo đơn vị',
            onSubmit: async (v) => {
                if (unit) await api(`/units/${unit.id}`, { method: 'PUT', body: { name: v.name } });
                else await api('/units', { method: 'POST', body: { name: v.name, segment: v.segment || null, parentId: parent?.id ?? null } });
                modal.close();
                toast(unit ? 'Đã đổi tên.' : 'Đã tạo đơn vị.', { type: 'success' });
                await draw(true);
            },
        });
        modal = openModal({ title: unit ? `Đổi tên ${unit.code}` : (parent ? `Thêm đơn vị con của ${parent.name}` : 'Thêm đơn vị cấp 1'),
            body: form.element });
    }

    await draw(false);
}
