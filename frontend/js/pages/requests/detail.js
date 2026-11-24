import { api } from '../../core/api.js';
import { formatDateTime } from '../../core/format.js';
import { REQUEST_STATUS } from '../../core/refdata.js';
import { backLink } from '../../ui/back-link.js';
import { dlRow } from '../../ui/dl.js';
import { h, replace } from '../../ui/escape.js';
import { createForm } from '../../ui/form.js';
import { confirmDialog, openModal } from '../../ui/modal.js';
import { pageHead } from '../../ui/page-head.js';
import { toast } from '../../ui/toast.js';

/**
 * Chi tiết một đề nghị thêm hoặc gỡ người học. Nút hiện theo quyền máy chủ trả về (access):
 * giáo viên của lớp và Super Admin duyệt, từ chối; người đề nghị hủy khi còn chờ.
 */

export async function render(container, params) {
    let req = await api(`/requests/${params.id}`);

    const reload = async () => {
        req = await api(`/requests/${params.id}`);
        draw();
    };

    const isAdd = () => req.type === 'ADD';

    function openApprove() {
        let modal;
        const form = createForm({
            fields: [{ name: 'note', label: 'Ghi chú (không bắt buộc)', type: 'textarea', maxLength: 500,
                hint: isAdd() ? null : 'Nếu người này đã có điểm, chỉ Super Admin duyệt được và phải ghi lý do gỡ ở đây.' }],
            submitLabel: 'Duyệt',
            onSubmit: async (v) => {
                await api(`/requests/${req.id}/approve`, { method: 'POST', body: { note: v.note || null } });
                modal.close();
                toast(isAdd() ? 'Đã duyệt: người này đã được thêm vào nội dung kiểm tra.' : 'Đã duyệt: người này đã được gỡ khỏi nội dung kiểm tra.',
                    { type: 'success' });
                await reload();
            },
        });
        modal = openModal({ title: 'Duyệt đề nghị', body: [
            h('p', null, isAdd() ? 'Thêm ' : 'Gỡ ', h('strong', null, req.target.fullName),
                isAdd() ? ' vào nội dung kiểm tra ' : ' khỏi nội dung kiểm tra ', h('strong', null, req.classRef.name), '.'),
            form.element] });
    }

    function openReject() {
        let modal;
        const form = createForm({
            fields: [{ name: 'note', label: 'Lý do từ chối', type: 'textarea', required: true, maxLength: 500,
                requiredMessage: 'Nhập lý do từ chối.' }],
            submitLabel: 'Từ chối',
            onSubmit: async (v) => {
                await api(`/requests/${req.id}/reject`, { method: 'POST', body: { note: v.note } });
                modal.close();
                toast('Đã từ chối đề nghị. Người đề nghị sẽ nhận được thông báo.', { type: 'success' });
                await reload();
            },
        });
        modal = openModal({ title: 'Từ chối đề nghị', body: form.element });
    }

    async function cancel() {
        if (!(await confirmDialog('Hủy đề nghị này? Giám khảo sẽ không xử lý nó nữa.',
            { title: 'Hủy đề nghị', okLabel: 'Hủy đề nghị', cancelLabel: 'Không', danger: true }))) return;
        try {
            await api(`/requests/${req.id}/cancel`, { method: 'POST' });
            toast('Đã hủy đề nghị.', { type: 'success' });
            await reload();
        } catch (e) {
            toast(e.message, { type: 'error' });
        }
    }

    function actions() {
        const list = [];
        if (req.access.decide) {
            list.push(h('button', { type: 'button', class: 'btn', onclick: openApprove }, 'Duyệt'),
                h('button', { type: 'button', class: 'btn btn-danger', onclick: openReject }, 'Từ chối'));
        }
        if (req.access.cancel) {
            list.push(h('button', { type: 'button', class: 'btn btn-secondary', onclick: cancel }, 'Hủy đề nghị'));
        }
        list.push(h('a', { class: 'btn btn-secondary', href: `#/classes/${req.classRef.id}` }, 'Xem nội dung kiểm tra'));
        return list;
    }

    function target() {
        const t = req.target;
        if (t.kind === 'UNIT') return [t.fullName, h('span', { class: 'text-muted' }, ' (cả đơn vị)')];
        return [t.fullName,
            t.rankSymbol ? h('span', { class: 'text-muted' }, ` · ${t.rankSymbol}`) : null,
            t.unitName ? h('span', { class: 'text-muted' }, ` · ${t.unitName}`) : null,
            t.personalCode ? [h('span', { class: 'text-muted' }, ' · '), h('span', { class: 'mono text-muted' }, t.personalCode)] : null];
    }

    function draw() {
        const st = REQUEST_STATUS[req.status] || { label: req.status, badge: 'badge' };
        const closed = req.classRef.status === 'CLOSED';
        replace(container,
            backLink(req.status === 'PENDING' ? `#/requests?focus=${req.id}` : '#/requests', 'Đề nghị'),
            pageHead(isAdd() ? 'Đề nghị thêm người học' : 'Đề nghị gỡ người học',
                [h('span', { class: st.badge }, st.label), ' ', req.classRef.name]),
            h('div', { class: 'btn-row page-actions' }, actions()),
            closed && req.status === 'PENDING' && isAdd()
                ? h('div', { class: 'alert alert-warning' }, h('p', null, 'Nội dung kiểm tra đã đóng: không duyệt thêm người được. Có thể từ chối đề nghị.'))
                : null,
            h('section', { class: 'card' },
                h('dl', { class: 'dl' },
                    dlRow('Nội dung kiểm tra', [req.classRef.name, closed ? h('span', { class: 'text-muted' }, ' (đã đóng)') : null]),
                    dlRow('Loại', isAdd() ? 'Thêm vào nội dung kiểm tra' : 'Gỡ khỏi nội dung kiểm tra'),
                    dlRow('Đối tượng', target()),
                    dlRow('Người đề nghị', `${req.requestedBy.fullName} (${req.requestedUnitName})`),
                    dlRow('Thời điểm đề nghị', formatDateTime(req.createdAt)),
                    req.status === 'PENDING' ? null : dlRow('Người xử lý', req.decidedBy?.fullName),
                    req.status === 'PENDING' ? null : dlRow('Thời điểm xử lý', formatDateTime(req.decidedAt)),
                    req.status === 'PENDING' ? null : dlRow(req.status === 'REJECTED' ? 'Lý do từ chối' : 'Ghi chú', req.decisionNote))));
    }

    draw();
}
