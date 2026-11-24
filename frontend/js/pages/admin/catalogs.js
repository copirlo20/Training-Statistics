import { api } from '../../core/api.js';
import { loadCatalogs } from '../../core/refdata.js';
import { h, replace } from '../../ui/escape.js';
import { createForm } from '../../ui/form.js';
import { openModal } from '../../ui/modal.js';
import { pageHead } from '../../ui/page-head.js';
import { rowActions } from '../../ui/row-actions.js';
import { toast } from '../../ui/toast.js';

/** Quản lý danh mục (Super Admin). Không xóa mục đã có, chỉ khóa, để dữ liệu cũ vẫn hiển thị đúng. */

const TYPES = [
    { path: 'ranks', key: 'ranks', label: 'Cấp bậc', symbol: true },
    { path: 'positions', key: 'positions', label: 'Chức vụ' },
    { path: 'specialties', key: 'specialties', label: 'Chuyên ngành' },
    { path: 'absence-reasons', key: 'absenceReasons', label: 'Lý do không kiểm tra' },
];

export async function render(container) {
    let current = TYPES[0];
    const tabs = h('div', { class: 'segmented', role: 'group', 'aria-label': 'Chọn danh mục' });
    const body = h('div');
    container.append(
        pageHead('Danh mục', 'Không xóa được mục đã có, chỉ khóa. Mục đã khóa không chọn được cho dữ liệu mới.'),
        tabs, body);

    function drawTabs() {
        replace(tabs, TYPES.map((t) => h('button', {
            type: 'button', class: 'btn btn-secondary', 'aria-pressed': String(t === current),
            onclick: () => { current = t; drawTabs(); draw(false); },
        }, t.label)));
    }

    async function draw(force) {
        const cat = await loadCatalogs(force);
        const items = cat[current.key];
        replace(body,
            h('div', { class: 'btn-row page-actions' },
                h('button', { type: 'button', class: 'btn', onclick: () => openEdit(null) }, `Thêm ${current.label.toLowerCase()}`)),
            h('div', { class: 'data-table' }, h('div', { class: 'table-scroll' }, h('table', { class: 'data' },
                h('caption', { class: 'visually-hidden' }, current.label),
                h('thead', null, h('tr', null,
                    h('th', { scope: 'col' }, 'Tên'),
                    current.symbol ? h('th', { scope: 'col' }, 'Ký hiệu') : null,
                    h('th', { scope: 'col' }, 'Thứ tự'),
                    h('th', { scope: 'col' }, 'Trạng thái'),
                    h('th', { scope: 'col' }, 'Thao tác'))),
                h('tbody', null, items.length === 0
                    ? h('tr', { class: 'row-message' }, h('td', { class: 'cell-message', colspan: current.symbol ? 5 : 4 }, 'Chưa có mục nào.'))
                    : items.map((it) => h('tr', null,
                        h('td', { 'data-label': 'Tên' }, it.name),
                        current.symbol ? h('td', { 'data-label': 'Ký hiệu' }, h('span', { class: 'mono' }, it.symbol)) : null,
                        h('td', { 'data-label': 'Thứ tự' }, String(it.sortOrder)),
                        h('td', { 'data-label': 'Trạng thái' },
                            h('span', { class: it.active ? 'badge badge-success' : 'badge badge-warning' }, it.active ? 'Đang dùng' : 'Đã khóa')),
                        h('td', { class: 'cell-actions' }, rowActions([
                            { icon: 'edit', label: 'Sửa', onClick: () => openEdit(it) },
                            { icon: it.active ? 'lock' : 'unlock', label: it.active ? 'Khóa' : 'Mở khóa', onClick: () => toggle(it) }])))))))));
    }

    async function toggle(it) {
        try {
            await api(`/catalogs/${current.path}/${it.id}/status`, { method: 'PATCH', body: { active: !it.active } });
            toast(it.active ? 'Đã khóa.' : 'Đã mở khóa.', { type: 'success' });
            await draw(true);
        } catch (e) {
            toast(e.message, { type: 'error' });
        }
    }

    function openEdit(it) {
        let modal;
        const fields = [{ name: 'name', label: 'Tên', required: true, maxLength: 100, value: it?.name, requiredMessage: 'Nhập tên.' }];
        if (current.symbol) {
            fields.push({ name: 'symbol', label: 'Ký hiệu', required: true, maxLength: 20, value: it?.symbol,
                requiredMessage: 'Nhập ký hiệu.', hint: 'Dùng khi nhập liệu và trong danh sách, ví dụ 3/ hoặc 2//CN.' });
        }
        fields.push({ name: 'sortOrder', label: 'Thứ tự hiển thị', type: 'number', inputmode: 'numeric', min: 0,
            value: it ? String(it.sortOrder) : '', hint: 'Để trống để xếp cuối.' });
        const form = createForm({
            fields,
            submitLabel: it ? 'Lưu' : 'Thêm',
            onSubmit: async (v) => {
                const payload = { name: v.name, symbol: v.symbol || null, sortOrder: v.sortOrder === '' ? null : Number(v.sortOrder) };
                if (it) await api(`/catalogs/${current.path}/${it.id}`, { method: 'PUT', body: payload });
                else await api(`/catalogs/${current.path}`, { method: 'POST', body: payload });
                modal.close();
                toast('Đã lưu.', { type: 'success' });
                await draw(true);
            },
        });
        modal = openModal({ title: it ? `Sửa ${current.label.toLowerCase()}` : `Thêm ${current.label.toLowerCase()}`, body: form.element });
    }

    drawTabs();
    await draw(false);
}
