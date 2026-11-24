import { api } from '../../core/api.js';
import { currentUser } from '../../core/auth.js';
import { formatDateTime } from '../../core/format.js';
import { REQUEST_STATUS } from '../../core/refdata.js';
import { currentQuery } from '../../core/router.js';
import { append, h, replace } from '../../ui/escape.js';
import { createForm } from '../../ui/form.js';
import { icon } from '../../ui/icons.js';
import { confirmDialog, failureReport, openModal } from '../../ui/modal.js';
import { pageHead } from '../../ui/page-head.js';
import { createTable } from '../../ui/table.js';
import { toast } from '../../ui/toast.js';

/**
 * Danh sách đề nghị. Máy chủ tự lọc: Admin thấy đề nghị mình tạo, giám khảo thấy đề nghị của nội dung mình, Super Admin
 * thấy tất cả. Tab "Đang chờ" gom theo nội dung kiểm tra: người duyệt chọn từng đề nghị hoặc cả nội dung rồi Duyệt,
 * Từ chối ngay tại trang (mỗi đề nghị vẫn đi qua API duyệt riêng của nó, nên mọi kiểm tra giữ nguyên). Các tab khác là bảng.
 */
const MAX_PAGES = 10;

export async function render(container) {
    const me = currentUser();
    let status = 'PENDING';
    const selected = new Set();
    let pending = [];
    // Mở từ thông báo đề nghị mới: hiện cả danh sách đang chờ, đánh dấu và cuộn tới nhóm có đề nghị này.
    const focusId = Number(currentQuery().get('focus')) || null;
    let scrolled = false;

    const subtitle = me.role === 'SUPER_ADMIN' ? 'Tất cả đề nghị thêm, gỡ người học.'
        : me.role === 'ADMIN' ? 'Đề nghị bạn đã gửi, và đề nghị của nội dung bạn làm giám khảo (nếu có).'
            : 'Đề nghị của các nội dung bạn làm giám khảo.';

    const what = (r) => [
        h('a', { class: 'cell-link', href: `#/requests/${r.id}` }, `${r.type === 'ADD' ? 'Thêm' : 'Gỡ'} ${r.target.fullName}`),
        h('span', { class: 'cell-sub' }, r.target.kind === 'UNIT' ? 'Cả đơn vị'
            : [r.target.rankSymbol, r.target.unitName].filter(Boolean).join(' · '))];

    const table = createTable({
        caption: 'Danh sách đề nghị',
        emptyText: 'Không có đề nghị nào.',
        columns: [
            { key: 'what', label: 'Đề nghị', render: what },
            { key: 'class', label: 'Nội dung kiểm tra', render: (r) => r.classRef.name },
            { key: 'by', label: 'Người đề nghị', priority: 3, render: (r) => [r.requestedBy.fullName,
                h('span', { class: 'cell-sub' }, r.requestedUnitName)] },
            { key: 'at', label: 'Thời điểm', render: (r) => formatDateTime(r.createdAt) },
            { key: 'status', label: 'Trạng thái', render: (r) => h('span', { class: REQUEST_STATUS[r.status]?.badge || 'badge' },
                REQUEST_STATUS[r.status]?.label || r.status) },
        ],
        load: ({ page, size }) => api('/requests', { query: { status, page, size } }),
    });

    const filters = h('div', { class: 'segmented', role: 'group', 'aria-label': 'Lọc theo trạng thái' });
    const drawFilters = () => replace(filters, [['PENDING', 'Đang chờ'], ['APPROVED', 'Đã duyệt'], ['REJECTED', 'Bị từ chối'],
        ['CANCELLED', 'Đã hủy'], ['', 'Tất cả']].map(([value, label]) => h('button', {
        type: 'button', class: 'btn btn-secondary', 'aria-pressed': String(status === value),
        onclick: () => { status = value; drawFilters(); show(); },
    }, label)));
    drawFilters();

    // ------------------------------------------------------------ đang chờ: gom theo nội dung kiểm tra
    const body = h('div', { 'aria-live': 'polite' });
    const barText = h('span', { class: 'assign-bar-text' });
    const approveBtn = h('button', { type: 'button', class: 'btn' }, icon('check', { small: true }), h('span', null, 'Duyệt'));
    const rejectBtn = h('button', { type: 'button', class: 'btn btn-danger' }, h('span', null, 'Từ chối'));
    const clearBtn = h('button', { type: 'button', class: 'btn btn-secondary' }, 'Bỏ chọn');
    const bar = h('div', { class: 'assign-bar', hidden: true, role: 'region', 'aria-label': 'Đề nghị đang chọn' },
        barText, h('div', { class: 'btn-row' }, clearBtn, rejectBtn, approveBtn));
    let refresh = [];

    function drawBar() {
        bar.hidden = selected.size === 0;
        replace(barText, `Đã chọn ${selected.size} đề nghị`);
        refresh.forEach((f) => f());
    }

    async function loadPending() {
        const all = [];
        for (let page = 0; page < MAX_PAGES; page++) {
            const r = await api('/requests', { query: { status: 'PENDING', page, size: 100 } });
            all.push(...r.items);
            if ((page + 1) * r.size >= r.total) break;
        }
        return all;
    }

    function groupCard(items) {
        const cls = items[0].classRef;
        const focused = items.some((r) => r.id === focusId);
        const decidable = items.filter((r) => r.access.decide);
        const allBox = h('input', { type: 'checkbox', id: `req-all-${cls.id}` });
        const syncAll = () => {
            const n = decidable.filter((r) => selected.has(r.id)).length;
            allBox.checked = decidable.length > 0 && n === decidable.length;
            allBox.indeterminate = n > 0 && n < decidable.length;
        };
        allBox.addEventListener('change', () => {
            decidable.forEach((r) => (allBox.checked ? selected.add(r.id) : selected.delete(r.id)));
            drawBar();
        });
        refresh.push(syncAll);
        syncAll();

        const rowEl = (r) => {
            const box = r.access.decide ? h('input', { type: 'checkbox', id: `req-${r.id}`, 'aria-label': `Chọn đề nghị ${r.type === 'ADD' ? 'thêm' : 'gỡ'} ${r.target.fullName}` }) : null;
            if (box) {
                box.checked = selected.has(r.id);
                box.addEventListener('change', () => { (box.checked ? selected.add(r.id) : selected.delete(r.id)); drawBar(); });
                refresh.push(() => { box.checked = selected.has(r.id); });
            }
            return h('li', { class: ['req-row', r.id === focusId ? 'req-row-focus' : null] },
                box ? h('label', { class: 'check req-check', for: box.id }, box) : h('span', { class: 'req-check' }),
                h('div', { class: 'req-main' },
                    h('span', { class: ['badge', r.type === 'ADD' ? 'badge-success' : 'badge-danger'] }, r.type === 'ADD' ? 'Thêm' : 'Gỡ'),
                    h('span', { class: 'req-target' }, h('a', { class: 'cell-link', href: `#/requests/${r.id}` }, r.target.fullName),
                        h('span', { class: 'cell-sub' }, r.target.kind === 'UNIT' ? 'Cả đơn vị'
                            : [r.target.rankSymbol, r.target.unitName].filter(Boolean).join(' · ')))),
                h('div', { class: 'req-by' }, h('span', null, r.requestedBy.fullName),
                    h('span', { class: 'cell-sub' }, `${r.requestedUnitName || ''} · ${formatDateTime(r.createdAt)}`)),
                r.access.cancel ? h('button', { type: 'button', class: 'icon-btn icon-btn-danger req-act', 'aria-label': `Hủy đề nghị ${r.target.fullName}`,
                    title: 'Hủy đề nghị', onclick: () => cancelOne(r) }, icon('close', { small: true })) : null);
        };

        return h('section', { class: ['card', 'req-group', focused ? 'req-group-focus' : null], 'data-class-id': cls.id },
            h('div', { class: 'req-head' },
                decidable.length ? h('label', { class: 'check req-all', for: allBox.id }, allBox, h('span', null, 'Chọn tất cả')) : null,
                h('div', { class: 'req-title' },
                    h('h2', null, h('a', { class: 'req-link', href: `#/classes/${cls.id}` }, cls.name)),
                    h('span', { class: 'cell-sub' }, `${items.length} đề nghị đang chờ${decidable.length < items.length ? `, bạn duyệt được ${decidable.length}` : ''}`))),
            h('ul', { class: 'req-list' }, items.map(rowEl)));
    }

    async function drawPending() {
        replace(body, h('p', { class: 'loading' }, 'Đang tải...'));
        try {
            pending = await loadPending();
        } catch (e) {
            replace(body, h('div', { class: 'alert alert-error' }, h('p', null, e.message)));
            return;
        }
        const ids = new Set(pending.map((r) => r.id));
        [...selected].forEach((id) => { if (!ids.has(id)) selected.delete(id); });
        refresh = [];
        const groups = new Map();
        for (const r of pending) {
            if (!groups.has(r.classRef.id)) groups.set(r.classRef.id, []);
            groups.get(r.classRef.id).push(r);
        }
        const gone = focusId && !ids.has(focusId)
            ? h('div', { class: 'alert alert-info' }, h('p', null, 'Đề nghị trong thông báo đã được xử lý. ',
                h('a', { href: `#/requests/${focusId}` }, 'Xem đề nghị đó'), '.'))
            : null;
        replace(body, gone, pending.length === 0 ? h('div', { class: 'card' }, h('p', { class: 'text-muted' }, 'Không có đề nghị nào đang chờ.'))
            : [...groups.values()].map(groupCard));
        drawBar();
        if (focusId && !scrolled) {
            scrolled = true;
            body.querySelector('.req-group-focus')?.scrollIntoView({ block: 'start' });
        }
    }

    async function runAll(verb, call) {
        const items = pending.filter((r) => selected.has(r.id));
        const failed = [];
        let done = 0;
        for (const r of items) {
            try {
                await call(r);
                selected.delete(r.id);
                done++;
            } catch (e) {
                failed.push({ who: `${r.type === 'ADD' ? 'Thêm' : 'Gỡ'} ${r.target.fullName} · ${r.classRef.name}`, message: e.message });
            }
        }
        if (done) toast(`Đã ${verb} ${done} đề nghị.`, { type: 'success' });
        if (failed.length) failureReport(`${failed.length} đề nghị chưa ${verb} được`, 'Các đề nghị này vẫn đang được chọn.', failed);
        await drawPending();
    }

    function decideDialog(approve) {
        const n = selected.size;
        let modal;
        const form = createForm({
            fields: [{ name: 'note', label: approve ? 'Ghi chú duyệt' : 'Lý do từ chối', type: 'textarea', maxLength: 500,
                required: !approve, requiredMessage: 'Nhập lý do từ chối.',
                hint: approve ? 'Không bắt buộc. Với đề nghị gỡ người đã có điểm, ghi chú này là lý do gỡ và bắt buộc (chỉ Super Admin).'
                    : 'Lý do được gửi cho người đề nghị, dùng chung cho mọi đề nghị đã chọn.' }],
            submitLabel: approve ? `Duyệt ${n} đề nghị` : `Từ chối ${n} đề nghị`,
            extraActions: [h('button', { type: 'button', class: 'btn btn-secondary', onclick: () => modal.close() }, 'Hủy')],
            onSubmit: async (v) => {
                modal.close();
                await runAll(approve ? 'duyệt' : 'từ chối', (r) => api(`/requests/${r.id}/${approve ? 'approve' : 'reject'}`,
                    { method: 'POST', body: { note: v.note || null } }));
            },
        });
        modal = openModal({ title: approve ? 'Duyệt đề nghị đã chọn' : 'Từ chối đề nghị đã chọn', body: form.element });
    }

    async function cancelOne(r) {
        if (!(await confirmDialog(`Hủy đề nghị ${r.type === 'ADD' ? 'thêm' : 'gỡ'} ${r.target.fullName}?`,
            { title: 'Hủy đề nghị', okLabel: 'Hủy đề nghị', cancelLabel: 'Không', danger: true }))) return;
        try {
            await api(`/requests/${r.id}/cancel`, { method: 'POST' });
            toast('Đã hủy đề nghị.', { type: 'success' });
            await drawPending();
        } catch (e) {
            toast(e.message, { type: 'error' });
        }
    }

    approveBtn.addEventListener('click', () => decideDialog(true));
    rejectBtn.addEventListener('click', () => decideDialog(false));
    clearBtn.addEventListener('click', () => { selected.clear(); drawBar(); });

    const host = h('div');
    function show() {
        if (status === 'PENDING') {
            replace(host, body, bar);
            drawPending();
        } else {
            selected.clear();
            replace(host, table.element);
            table.reload(true);
        }
    }

    append(container, [
        pageHead('Đề nghị', subtitle, me.role === 'ADMIN'
            ? [h('a', { class: 'btn', href: '#/assign' }, 'Phân công theo bảng'),
                h('a', { class: 'btn btn-secondary', href: '#/requests/new' }, 'Tạo đề nghị')]
            : null),
        filters,
        host]);
    show();
}
