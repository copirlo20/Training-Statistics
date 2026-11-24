import { api } from '../../core/api.js';
import { currentUser } from '../../core/auth.js';
import { formatDate } from '../../core/format.js';
import { OPEN_STATUS } from '../../core/refdata.js';
import { currentQuery } from '../../core/router.js';
import { append, h, replace } from '../../ui/escape.js';
import { filterField, filterSelect } from '../../ui/filter-field.js';
import { icon } from '../../ui/icons.js';
import { confirmDialog } from '../../ui/modal.js';
import { pageHead } from '../../ui/page-head.js';
import { rowActions } from '../../ui/row-actions.js';
import { createTable } from '../../ui/table.js';
import { toast } from '../../ui/toast.js';
import { openClassModal } from './class-modal.js';

/**
 * Danh sách lớp. Máy chủ tự lọc theo vai trò: Super Admin và Admin thấy tất cả (Admin chỉ đếm người trong nhánh),
 * giáo viên thấy lớp mình dạy, người dùng thấy lớp mình tham gia.
 */
export async function render(container) {
    const me = currentUser();
    const periods = await api('/periods');
    const query = currentQuery();
    const filters = { periodId: query.get('periodId') || '', status: '', teaching: '', ungraded: query.get('ungraded') === 'true' ? 'true' : '' };

    const subtitle = {
        SUPER_ADMIN: 'Tất cả các nội dung kiểm tra.',
        ADMIN: 'Tất cả các nội dung kiểm tra. Số người học và chi tiết chỉ tính người thuộc đơn vị bạn quản lý.',
        USER: 'Nội dung kiểm tra bạn làm giám khảo hoặc tham gia.',
    }[me.role];

    const sa = me.role === 'SUPER_ADMIN';

    async function run(confirmText, title, danger, call, done) {
        if (!(await confirmDialog(confirmText, { title, okLabel: title, danger }))) return;
        try {
            await call();
            toast(done, { type: 'success' });
            table.reload();
        } catch (e) {
            toast(e.message, { type: 'error' });
        }
    }

    const newClass = () => openClassModal({ presetPeriodId: filters.periodId, onSaved: () => table.reload() });
    async function editClass(id) {
        try {
            openClassModal({ cls: await api(`/classes/${id}`), onSaved: () => table.reload() });
        } catch (e) {
            toast(e.message, { type: 'error' });
        }
    }

    // Tiến độ chấm trong phạm vi người xem được đếm: "Còn N chưa chấm" (chưa có điểm, chưa ghi lý do vắng) nổi bật.
    function progressCell(r) {
        if (r.participantCount === 0) return h('span', { class: 'text-muted' }, 'Chưa có người học');
        // Bề rộng đặt qua CSSOM: CSP không cho thuộc tính style nội tuyến.
        const fill = h('span', { class: ['progress-mini-fill', r.pendingCount > 0 ? null : 'is-complete'] });
        fill.style.width = `${Math.round((r.gradedCount * 100) / r.participantCount)}%`;
        return h('span', { class: 'progress-cell' },
            r.pendingCount > 0
                ? h('span', { class: 'badge badge-warning' }, icon('alert', { small: true }), `Còn ${r.pendingCount} chưa chấm`)
                : h('span', { class: 'badge badge-success' }, icon('check', { small: true }), 'Đã chấm xong'),
            h('span', { class: 'progress-meter' },
                h('span', { class: 'progress-mini', 'aria-hidden': 'true' }, fill),
                h('span', { class: 'progress-count', title: `${r.gradedCount} trên ${r.participantCount} người đã có điểm` },
                    `${r.gradedCount}/${r.participantCount}`, h('span', { class: 'visually-hidden' }, ' người đã có điểm'))));
    }

    const toggle = (r) => (r.status === 'OPEN'
        ? run('Đóng nội dung kiểm tra? Nội dung kiểm tra đóng không nhận thêm người học và đề nghị mới. Có thể mở lại.', 'Đóng nội dung kiểm tra', false,
            () => api(`/classes/${r.id}/status`, { method: 'PATCH', body: { status: 'CLOSED' } }), 'Đã đóng nội dung kiểm tra.')
        : run('Mở lại nội dung kiểm tra để nhận thêm người học và đề nghị?', 'Mở lại nội dung kiểm tra', false,
            () => api(`/classes/${r.id}/status`, { method: 'PATCH', body: { status: 'OPEN' } }), 'Đã mở lại nội dung kiểm tra.'));
    const remove = (r) => run(`Xóa nội dung kiểm tra "${r.name}"? Các đề nghị đang chờ của nội dung kiểm tra sẽ bị hủy. Nội dung kiểm tra đã có điểm thì không xóa được.`,
        'Xóa nội dung kiểm tra', true, () => api(`/classes/${r.id}/delete`, { method: 'POST' }), 'Đã xóa nội dung kiểm tra.');

    const table = createTable({
        caption: 'Danh sách nội dung kiểm tra',
        emptyText: 'Không có nội dung kiểm tra nào khớp bộ lọc.',
        columns: [
            { key: 'name', label: 'Nội dung kiểm tra', render: (r) => [
                h('a', { class: 'cell-link', href: `#/classes/${r.id}` }, r.name),
                r.teaching ? h('span', { class: 'cell-sub' }, 'Bạn là giám khảo') : null] },
            { key: 'period', label: 'Đợt', priority: 3, render: (r) => r.period.name },
            { key: 'examDate', label: 'Ngày kiểm tra', render: (r) => formatDate(r.examDate) || h('span', { class: 'text-muted' }, 'Chưa đặt') },
            { key: 'count', label: 'Người học', render: (r) => String(r.participantCount) },
            { key: 'progress', label: 'Tiến độ chấm', render: progressCell },
            { key: 'status', label: 'Trạng thái', render: (r) => [h('span', { class: OPEN_STATUS[r.status]?.badge || 'badge' },
                OPEN_STATUS[r.status]?.label || r.status),
            r.period.status === 'CLOSED' ? h('span', { class: 'cell-sub' }, 'Đợt đã khóa') : null] },
            { key: 'actions', label: 'Thao tác', actions: true, render: (r) => rowActions([
                { icon: 'eye', label: 'Xem chi tiết', href: `#/classes/${r.id}` },
                sa || r.teaching ? { icon: 'star', label: 'Chấm điểm', href: `#/classes/${r.id}/grade` } : null,
                sa ? { icon: 'edit', label: 'Sửa', onClick: () => editClass(r.id) } : null,
                // Đợt đã khóa thì nội dung đóng theo đợt, không mở lại riêng được (mở lại đợt trước).
                sa && r.period.status !== 'CLOSED' ? { icon: r.status === 'OPEN' ? 'lock' : 'unlock',
                    label: r.status === 'OPEN' ? 'Đóng nội dung kiểm tra' : 'Mở lại nội dung kiểm tra', onClick: () => toggle(r) } : null,
                sa ? { icon: 'trash', label: 'Xóa', tone: 'danger', onClick: () => remove(r) } : null]) },
        ],
        load: ({ page, size }) => api('/classes', { query: { ...filters, page, size } }),
        // Nội dung còn người phải chấm thì tô nền cảnh báo (kèm chữ "Còn N chưa chấm" trong ô tiến độ).
        rowClass: (r) => (r.pendingCount > 0 ? 'row-attention' : null),
    });

    const periodSel = filterSelect('c-period', periods.map((p) => ({ value: String(p.id), label: p.name })), 'Tất cả đợt', filters.periodId);
    const statusSel = filterSelect('c-status', [{ value: 'OPEN', label: 'Đang mở' }, { value: 'CLOSED', label: 'Đã đóng' }], 'Tất cả');
    const teachingSel = filterSelect('c-teaching', [{ value: 'true', label: 'Chỉ nội dung tôi làm giám khảo' }], 'Mọi nội dung tôi được xem');

    const apply = () => {
        Object.assign(filters, { periodId: periodSel.value, status: statusSel.value,
            teaching: teachingSel.value });
        table.reload(true);
    };
    for (const s of [periodSel, statusSel, teachingSel]) s.addEventListener('change', apply);
    // Chọn một trong hai: mọi nội dung, hoặc chỉ nội dung còn người chưa chấm (giám khảo theo dõi việc còn lại).
    const progressSeg = h('div', { class: 'segmented', role: 'radiogroup', 'aria-label': 'Tiến độ chấm' });
    const drawProgressSeg = () => replace(progressSeg, [['', 'Tất cả'], ['true', 'Chưa chấm xong']].map(([value, label]) => h('button', {
        type: 'button', class: 'btn btn-secondary', role: 'radio', 'aria-checked': String(filters.ungraded === value),
        'aria-pressed': String(filters.ungraded === value),
        onclick: () => { if (filters.ungraded !== value) { filters.ungraded = value; drawProgressSeg(); table.reload(true); } },
    }, label)));
    drawProgressSeg();

    const details = h('details', { class: 'filters', open: window.matchMedia('(min-width: 600px)').matches || !!filters.periodId },
        h('summary', null, icon('search', { small: true }), 'Lọc nội dung kiểm tra'),
        h('form', { class: 'toolbar', onsubmit: (e) => { e.preventDefault(); apply(); } },
            filterField('c-period', 'Đợt kiểm tra', periodSel),
            filterField('c-status', 'Trạng thái', statusSel),
            filterField('c-teaching', 'Phạm vi', teachingSel),
            h('div', { class: 'field field-action' }, h('span', { class: 'field-caption' }, 'Tiến độ chấm'), progressSeg)));

    const actions = [];
    if (me.role === 'SUPER_ADMIN') {
        actions.push(h('a', { class: 'btn btn-secondary', href: '#/periods' }, 'Đợt kiểm tra'),
            h('a', { class: 'btn btn-secondary', href: '#/classes/import' }, 'Nhập Excel'),
            h('button', { type: 'button', class: 'btn', onclick: () => newClass() }, icon('class', { small: true }), h('span', null, 'Thêm nội dung kiểm tra')));
    }
    if (me.role === 'ADMIN') {
        actions.push(h('a', { class: 'btn', href: '#/requests/new' }, 'Tạo đề nghị'));
    }

    append(container, [
        pageHead('Nội dung kiểm tra', subtitle, actions),
        details,
        table.element]);
    table.reload(true);
    if (sa && query.get('new') === '1') newClass();
}
