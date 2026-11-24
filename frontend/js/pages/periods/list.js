import { api } from '../../core/api.js';
import { formatDate } from '../../core/format.js';
import { OPEN_STATUS } from '../../core/refdata.js';
import { append, h, replace } from '../../ui/escape.js';
import { createForm } from '../../ui/form.js';
import { confirmDialog, openModal } from '../../ui/modal.js';
import { pageHead } from '../../ui/page-head.js';
import { rowActions } from '../../ui/row-actions.js';
import { toast } from '../../ui/toast.js';

/**
 * Đợt kiểm tra (Super Admin): thêm, sửa, đóng, mở, xóa mềm. Đợt đóng không nhận lớp mới.
 * Chỉ xóa được đợt không còn lớp (máy chủ kiểm, trả 409).
 */
export async function render(container) {
    const body = h('div');

    append(container, [
        pageHead('Đợt kiểm tra',
            'Đóng đợt thì mọi nội dung kiểm tra của đợt cũng đóng và đợt không nhận nội dung mới. Chỉ xóa được đợt chưa có nội dung kiểm tra.',
            h('button', { type: 'button', class: 'btn', onclick: () => openEdit(null) }, 'Thêm đợt kiểm tra')),
        body]);

    const range = (p) => {
        if (!p.startDate && !p.endDate) return h('span', { class: 'text-muted' }, 'Chưa đặt');
        return `${formatDate(p.startDate) || '…'} – ${formatDate(p.endDate) || '…'}`;
    };

    async function draw() {
        replace(body, h('div', { class: 'loading' }, 'Đang tải...'));
        const periods = await api('/periods');
        replace(body, h('div', { class: 'data-table' }, h('div', { class: 'table-scroll' }, h('table', { class: 'data' },
            h('caption', { class: 'visually-hidden' }, 'Danh sách đợt kiểm tra'),
            h('thead', null, h('tr', null,
                ['Tên đợt', 'Thời gian', 'Số nội dung kiểm tra', 'Trạng thái', 'Thao tác'].map((t) => h('th', { scope: 'col' }, t)))),
            h('tbody', null, periods.length === 0
                ? h('tr', { class: 'row-message' }, h('td', { class: 'cell-message', colspan: 5 }, 'Chưa có đợt kiểm tra nào.'))
                : periods.map((p) => h('tr', null,
                    h('td', { 'data-label': 'Tên đợt' }, h('div', { class: 'cell-value' },
                        h('a', { class: 'cell-link', href: `#/classes?periodId=${p.id}` }, p.name),
                        p.note ? h('span', { class: 'cell-sub' }, p.note) : null)),
                    h('td', { 'data-label': 'Thời gian' }, h('div', { class: 'cell-value' }, range(p))),
                    h('td', { 'data-label': 'Số nội dung kiểm tra' }, h('div', { class: 'cell-value' }, String(p.classCount))),
                    h('td', { 'data-label': 'Trạng thái' }, h('div', { class: 'cell-value' },
                        h('span', { class: OPEN_STATUS[p.status]?.badge || 'badge' }, OPEN_STATUS[p.status]?.label || p.status))),
                    h('td', { class: 'cell-actions', 'data-label': 'Thao tác' }, h('div', { class: 'cell-value' }, rowActions([
                        { icon: 'eye', label: 'Xem các nội dung kiểm tra', href: `#/classes?periodId=${p.id}` },
                        p.classCount > 0 ? { icon: 'star', label: 'Chấm điểm cả đợt bằng Excel',
                            href: `#/periods/import-results?periodId=${p.id}` } : null,
                        { icon: 'edit', label: 'Sửa', onClick: () => openEdit(p) },
                        { icon: p.status === 'OPEN' ? 'lock' : 'unlock', label: p.status === 'OPEN' ? 'Đóng đợt' : 'Mở lại đợt',
                            onClick: () => toggle(p) },
                        p.classCount === 0 ? { icon: 'trash', label: 'Xóa', tone: 'danger', onClick: () => remove(p) } : null]))))))))));
    }

    async function toggle(p) {
        const status = p.status === 'OPEN' ? 'CLOSED' : 'OPEN';
        const text = status === 'CLOSED'
            ? `Đóng đợt "${p.name}"? Mọi nội dung kiểm tra của đợt (${p.classCount}) cũng đóng theo: không thêm người, không nhận đề nghị mới, không mở lại riêng từng nội dung được. Có thể mở lại đợt.`
            : `Mở lại đợt "${p.name}"? Các nội dung kiểm tra của đợt trở về trạng thái riêng của chúng (nội dung đã đóng trước đó vẫn đóng).`;
        if (!(await confirmDialog(text, { title: status === 'CLOSED' ? 'Đóng đợt kiểm tra' : 'Mở lại đợt kiểm tra',
            okLabel: status === 'CLOSED' ? 'Đóng đợt' : 'Mở lại' }))) return;
        try {
            await api(`/periods/${p.id}/status`, { method: 'PATCH', body: { status } });
            toast(status === 'CLOSED' ? 'Đã đóng đợt.' : 'Đã mở lại đợt.', { type: 'success' });
            await draw();
        } catch (e) {
            toast(e.message, { type: 'error' });
        }
    }

    async function remove(p) {
        if (!(await confirmDialog(`Xóa đợt "${p.name}"? Bản ghi vẫn được giữ trong hệ thống.`,
            { title: 'Xóa đợt kiểm tra', okLabel: 'Xóa', danger: true }))) return;
        try {
            await api(`/periods/${p.id}/delete`, { method: 'POST' });
            toast('Đã xóa đợt.', { type: 'success' });
            await draw();
        } catch (e) {
            toast(e.message, { type: 'error' });
        }
    }

    function openEdit(p) {
        let modal;
        const form = createForm({
            fields: [
                { name: 'name', label: 'Tên đợt', required: true, maxLength: 100, value: p?.name, requiredMessage: 'Nhập tên đợt.' },
                { name: 'startDate', label: 'Ngày bắt đầu', type: 'date', value: p?.startDate },
                { name: 'endDate', label: 'Ngày kết thúc', type: 'date', value: p?.endDate,
                    validate: (v, all) => (v && all.startDate && v < all.startDate ? 'Ngày kết thúc phải sau ngày bắt đầu.' : null) },
                { name: 'note', label: 'Ghi chú', type: 'textarea', maxLength: 500, value: p?.note },
            ],
            submitLabel: p ? 'Lưu' : 'Thêm',
            onSubmit: async (v) => {
                const payload = { name: v.name, startDate: v.startDate || null, endDate: v.endDate || null, note: v.note || null };
                if (p) await api(`/periods/${p.id}`, { method: 'PUT', body: { ...payload, version: p.version } });
                else await api('/periods', { method: 'POST', body: payload });
                modal.close();
                toast('Đã lưu.', { type: 'success' });
                await draw();
            },
        });
        modal = openModal({ title: p ? 'Sửa đợt kiểm tra' : 'Thêm đợt kiểm tra', body: form.element });
    }

    await draw();
}
