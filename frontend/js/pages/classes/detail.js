import { api } from '../../core/api.js';
import { currentUser } from '../../core/auth.js';
import { formatDate, formatDateTime } from '../../core/format.js';
import { GRADE, GRADE_ORDER, loadCatalogs, OPEN_STATUS, PARTICIPANT_STATE, snapshotUnitChoices } from '../../core/refdata.js';
import { currentQuery, navigate, readQuery, replaceQuery, toQueryString } from '../../core/router.js';
import { actionMenu } from '../../ui/action-menu.js';
import { backLink } from '../../ui/back-link.js';
import { dlRow } from '../../ui/dl.js';
import { h, replace } from '../../ui/escape.js';
import { filterField } from '../../ui/filter-field.js';
import { confirmDialog } from '../../ui/modal.js';
import { multiSelect } from '../../ui/multi-select.js';
import { pageHead } from '../../ui/page-head.js';
import { printLink } from '../../ui/print-sheet.js';
import { rowActions } from '../../ui/row-actions.js';
import { createTable } from '../../ui/table.js';
import { toast } from '../../ui/toast.js';
import { openClassModal } from './class-modal.js';
import { openAbsence, openCandidates, openRemove, openTeachers } from './dialogs.js';
import {
    CATEGORY_VALUES, categoryLabel, categoryOf, emptyParticipantFilter, participantMatches,
} from './participant-filter.js';
import { openEditResult, stateCell } from './results.js';

/**
 * Chi tiết lớp. Nút hiện theo quyền máy chủ trả về (access): edit (Super Admin), manage (Super Admin, giáo viên của
 * lớp), request (Admin, lớp đang mở). Danh sách người học máy chủ đã lọc: Admin chỉ thấy người trong nhánh.
 */
export async function render(container, params) {
    const me = currentUser();
    const cat = await loadCatalogs();
    let cls = await api(`/classes/${params.id}`);
    let state = 'ACTIVE';
    // In kết quả theo đúng bộ lọc đang chọn ở danh sách người học.
    const printBtn = printLink(`#/classes/${cls.id}/print`, 'In kết quả', { withIcon: false });

    const reload = async () => {
        cls = await api(`/classes/${params.id}`);
        draw();
    };

    const btn = (label, onClick, variant = 'secondary') =>
        h('button', { type: 'button', class: ['btn', `btn-${variant}`], onclick: onClick }, label);

    async function setStatus(status) {
        const closing = status === 'CLOSED';
        if (!(await confirmDialog(closing
            ? 'Đóng nội dung kiểm tra? Nội dung kiểm tra đóng không nhận thêm người học và đề nghị mới. Có thể mở lại.'
            : 'Mở lại nội dung kiểm tra để nhận thêm người học và đề nghị?', { title: closing ? 'Đóng nội dung kiểm tra' : 'Mở lại nội dung kiểm tra', okLabel: closing ? 'Đóng nội dung kiểm tra' : 'Mở lại' }))) return;
        try {
            await api(`/classes/${cls.id}/status`, { method: 'PATCH', body: { status } });
            toast(closing ? 'Đã đóng nội dung kiểm tra.' : 'Đã mở lại nội dung kiểm tra.', { type: 'success' });
            await reload();
        } catch (e) {
            toast(e.message, { type: 'error' });
        }
    }

    async function removeClass() {
        if (!(await confirmDialog(`Xóa nội dung kiểm tra "${cls.name}"? Các đề nghị đang chờ của nội dung kiểm tra sẽ bị hủy. Nội dung kiểm tra đã có điểm thì không xóa được.`,
            { title: 'Xóa nội dung kiểm tra', okLabel: 'Xóa nội dung kiểm tra', danger: true }))) return;
        try {
            await api(`/classes/${cls.id}/delete`, { method: 'POST' });
            toast('Đã xóa nội dung kiểm tra.', { type: 'success' });
            navigate('/classes');
        } catch (e) {
            toast(e.message, { type: 'error' });
        }
    }

    async function requestRemove(p) {
        if (!(await confirmDialog(`Gửi đề nghị gỡ ${p.user.fullName} khỏi nội dung kiểm tra? Giám khảo của nội dung kiểm tra sẽ duyệt.`,
            { title: 'Đề nghị gỡ', okLabel: 'Gửi đề nghị' }))) return;
        try {
            const target = p.user.kind === 'UNIT' ? { unitId: p.snapshotUnit.id } : { userId: p.user.id };
            const r = await api(`/classes/${cls.id}/requests`, { method: 'POST', body: { type: 'REMOVE', ...target } });
            toast('Đã gửi đề nghị.', { type: 'success' });
            navigate(`/requests/${r.id}`);
        } catch (e) {
            toast(e.message, { type: 'error' });
        }
    }

    function actions() {
        const open = cls.status === 'OPEN';
        const list = [];
        if (cls.access.manage) {
            list.push(h('a', { class: 'btn btn-secondary', href: `#/classes/${cls.id}/grade` }, 'Chấm điểm'),
                h('a', { class: 'btn btn-secondary', href: `#/classes/${cls.id}/import-results` }, 'Nhập điểm Excel'));
        }
        if (cls.access.manage || me.role !== 'USER') {
            list.push(h('a', { class: 'btn btn-secondary', href: `#/stats/class/${cls.id}` }, 'Thống kê'),
                // Máy chủ lọc như danh sách bên dưới: Admin chỉ có người trong nhánh mình.
                h('a', { class: 'btn btn-secondary', href: `/api/v1/classes/${cls.id}/results/export`, download: '' },
                    'Xuất điểm Excel'),
                printBtn.element);
        }
        if (cls.access.manage && open) {
            list.push(btn('Thêm người', () => openCandidates(cls, 'PERSON', reload)),
                btn('Thêm đơn vị', () => openCandidates(cls, 'UNIT', reload)));
        }
        if (cls.access.request) {
            list.push(h('a', { class: 'btn', href: `#/requests/new?classId=${cls.id}` }, 'Đề nghị thêm người'));
        }
        if (cls.access.edit) {
            list.push(actionMenu('Quản lý nội dung kiểm tra', [
                { label: 'Sửa nội dung và giám khảo', icon: 'edit', onClick: () => openClassModal({ cls, onSaved: reload }) },
                { label: 'Gán giám khảo', icon: 'users', onClick: () => openTeachers(cls, reload) },
                cls.period.status === 'CLOSED' ? null
                    : { label: open ? 'Đóng nội dung kiểm tra' : 'Mở lại nội dung kiểm tra', icon: open ? 'lock' : 'unlock', onClick: () => setStatus(open ? 'CLOSED' : 'OPEN') },
                { label: 'Xóa nội dung kiểm tra', icon: 'trash', tone: 'danger', onClick: removeClass }]));
        }
        return list;
    }

    function participantActions(p) {
        if (p.removed) return null;
        if (cls.access.manage) {
            return rowActions([
                p.result && me.role === 'SUPER_ADMIN' ? { icon: 'edit', label: 'Sửa điểm', onClick: () => openEditResult(p, reload) } : null,
                p.result ? null : { icon: 'calendar', label: p.absence ? 'Sửa lý do vắng' : 'Lý do vắng',
                    onClick: () => openAbsence(cls, p, cat, reload) },
                // Người đã có điểm chỉ Super Admin gỡ được (máy chủ kiểm lại).
                !p.result || me.role === 'SUPER_ADMIN' ? { icon: 'trash', label: 'Gỡ', tone: 'danger', onClick: () => openRemove(cls, p, reload) } : null,
            ]);
        }
        if (cls.access.request) {
            return rowActions([{ icon: 'inbox', label: 'Đề nghị gỡ', onClick: () => requestRemove(p) }]);
        }
        return null;
    }

    // Bộ lọc người học giữ qua các lần vẽ lại (sau khi gỡ, chấm, đổi tab) và trên địa chỉ trang (quay lại từ trang in
    // vẫn còn). Dữ liệu tải một lần mỗi tab rồi lọc ở trình duyệt.
    const pf = readQuery(emptyParticipantFilter());
    let cache = {};

    async function loadParticipants() {
        if (!cache[state]) cache[state] = await api(`/classes/${cls.id}/participants`, { query: { state } });
        return cache[state];
    }

    // Lựa chọn lọc kèm số người trong tab đang xem; đối tượng chỉ liệt kê loại có trong danh sách.
    function filterChoices(items) {
        const count = (fn) => items.filter(fn).length;
        const states = Object.entries(PARTICIPANT_STATE).map(([value, s]) => ({ value,
            label: `${s.label} (${count((p) => p.state === value)})` }));
        const grades = GRADE_ORDER.map((value) => ({ value,
            label: `${GRADE[value].label} (${count((p) => p.result?.grade === value)})` }));
        const present = new Set(items.map(categoryOf));
        const categories = CATEGORY_VALUES.filter((value) => present.has(value))
            .map((value) => ({ value, label: `${categoryLabel(value)} (${count((p) => categoryOf(p) === value)})` }));
        return { states, grades, categories };
    }

    function participantsTable(onLoaded) {
        const columns = [
            { key: 'name', label: 'Họ tên', render: (p) => [
                p.user.fullName,
                p.user.kind === 'UNIT' ? [' ', h('span', { class: 'badge' }, 'Cả đơn vị')] : null] },
            { key: 'rank', label: 'Cấp bậc', priority: 3, render: (p) => p.user.rankSymbol
                ? h('span', { class: 'mono', title: p.user.rankName }, p.user.rankSymbol) : '' },
            { key: 'unit', label: 'Đơn vị lúc tham gia', render: (p) => [
                p.snapshotUnit.name,
                p.user.unitName && p.user.unitName !== p.snapshotUnit.name
                    ? h('span', { class: 'cell-sub' }, `Hiện ở: ${p.user.unitName}`) : null] },
        ];
        if (state === 'ACTIVE') {
            columns.push({ key: 'state', label: 'Tình trạng', render: stateCell });
            columns.push({ key: 'actions', label: 'Thao tác', actions: true, render: participantActions });
        } else {
            columns.push({ key: 'removed', label: 'Đã gỡ', render: (p) => [
                formatDateTime(p.removed.at),
                h('span', { class: 'cell-sub' }, [p.removed.byName, p.removed.reason].filter(Boolean).join(': '))] });
            columns.push({ key: 'state', label: 'Điểm', render: (p) => (p.result ? stateCell(p) : '') });
        }
        const table = createTable({
            caption: 'Người học trong nội dung kiểm tra',
            emptyText: state === 'ACTIVE' ? 'Nội dung kiểm tra chưa có người học nào.' : 'Chưa gỡ ai khỏi nội dung kiểm tra.',
            columns,
            load: async () => {
                const all = await loadParticipants();
                onLoaded.before(all);
                const items = all.filter((p) => participantMatches(p, pf));
                onLoaded.after(all, items);
                return { items, page: 0, size: Math.max(items.length, 1), total: items.length };
            },
        });
        table.reload();
        return table;
    }

    async function pendingRequests() {
        if (!cls.access.manage) return null;
        try {
            const res = await api('/requests', { query: { classId: cls.id, status: 'PENDING', size: 50 } });
            if (res.items.length === 0) return null;
            return h('section', { class: 'card' },
                h('div', { class: 'card-head' },
                    h('h2', null, `Đề nghị đang chờ duyệt (${res.total})`),
                    h('a', { class: 'btn btn-secondary', href: `#/requests?focus=${res.items[0].id}` }, 'Duyệt nhiều tại trang Đề nghị')),
                h('ul', { class: 'pick-list' }, res.items.map((r) => h('li', { class: 'pick-row' },
                    h('div', { class: 'pick-info' },
                        h('span', { class: 'pick-title' }, `${r.type === 'ADD' ? 'Thêm' : 'Gỡ'} ${r.target.fullName}`),
                        h('span', { class: 'cell-sub' }, `${r.requestedBy.fullName} (${r.requestedUnitName}) · ${formatDateTime(r.createdAt)}`)),
                    h('div', { class: 'pick-action' }, h('a', { class: 'btn btn-secondary', href: `#/requests/${r.id}` }, 'Xem, duyệt'))))));
        } catch {
            return null;
        }
    }

    const pendingSlot = h('div');
    const participantsSlot = h('div');

    function drawParticipants() {
        const tabs = me.role === 'SUPER_ADMIN'
            ? h('div', { class: 'segmented', role: 'group', 'aria-label': 'Lọc người học' },
                [['ACTIVE', 'Đang tham gia'], ['REMOVED', 'Đã gỡ']].map(([value, label]) => h('button', {
                    type: 'button', class: 'btn btn-secondary', 'aria-pressed': String(state === value),
                    onclick: () => { state = value; drawParticipants(); },
                }, label)))
            : null;
        const search = h('input', { id: 'p-q', class: 'input', type: 'search', autocomplete: 'off', value: pf.q,
            placeholder: 'Họ tên, mã định danh hoặc đơn vị' });
        const unitSel = multiSelect({ id: 'p-unit', allLabel: 'Tất cả đơn vị', unit: 'đơn vị', values: pf.units,
            onChange: (v) => { pf.units = v; table.reload(); } });
        const stateSel = multiSelect({ id: 'p-state', allLabel: 'Mọi tình trạng', unit: 'tình trạng', values: pf.states,
            searchable: false, onChange: (v) => { pf.states = v; table.reload(); } });
        const gradeSel = multiSelect({ id: 'p-grade', allLabel: 'Mọi xếp loại', unit: 'xếp loại', values: pf.grades,
            searchable: false, onChange: (v) => { pf.grades = v; table.reload(); } });
        const catSel = multiSelect({ id: 'p-cat', allLabel: 'Mọi đối tượng', unit: 'đối tượng', values: pf.categories,
            searchable: false, onChange: (v) => { pf.categories = v; table.reload(); } });
        const reset = h('button', { type: 'button', class: 'btn btn-secondary', onclick: () => {
            search.value = '';
            Object.assign(pf, emptyParticipantFilter());
            for (const s of [unitSel, stateSel, gradeSel, catSel]) s.setValues([]);
            table.reload();
        } }, 'Bỏ lọc');
        let timer;
        search.addEventListener('input', () => {
            clearTimeout(timer);
            timer = setTimeout(() => { pf.q = search.value.trim(); table.reload(); }, 200);
        });
        const summary = h('p', { class: 'field-hint p-summary', 'aria-live': 'polite' });
        const table = participantsTable({
            // Đơn vị đã chọn mà không còn trong danh sách (đổi tab) thì bỏ khỏi bộ lọc trước khi lọc.
            before: (all) => {
                const c = filterChoices(all);
                unitSel.setOptions(snapshotUnitChoices(all));
                stateSel.setOptions(c.states);
                gradeSel.setOptions(c.grades);
                catSel.setOptions(c.categories);
                Object.assign(pf, { units: unitSel.values(), states: stateSel.values(), grades: gradeSel.values(),
                    categories: catSel.values() });
            },
            after: (all, shown) => {
                replace(summary, shown.length === all.length ? '' : `Đang hiện ${shown.length}/${all.length} người khớp bộ lọc.`);
                replaceQuery(pf);
                printBtn.update(toQueryString(pf));
            },
        });
        replace(participantsSlot, tabs,
            h('div', { class: 'toolbar toolbar-dense p-toolbar' },
                filterField('p-q', 'Tìm nhanh', search),
                filterField('p-unit', 'Đơn vị lúc tham gia', unitSel.element),
                filterField('p-state', 'Tình trạng', stateSel.element),
                filterField('p-grade', 'Xếp loại', gradeSel.element),
                filterField('p-cat', 'Đối tượng', catSel.element),
                h('div', { class: 'field field-action' }, reset)),
            summary, table.element);
    }

    function draw() {
        cache = {};
        const st = OPEN_STATUS[cls.status] || { label: cls.status, badge: 'badge' };
        const teachers = cls.teachers.length === 0
            ? h('span', { class: 'text-muted' }, 'Chưa gán (Super Admin quản lý)')
            : cls.teachers.map((t, i) => [i > 0 ? ', ' : '', t.fullName, t.rankSymbol ? ` (${t.rankSymbol})` : '']);
        replace(container,
            backLink('#/classes', 'Nội dung kiểm tra'),
            pageHead(cls.name, [h('span', { class: st.badge }, st.label), ` ${cls.period.name}`]),
            h('div', { class: 'btn-row page-actions' }, actions()),
            h('section', { class: 'card' },
                h('dl', { class: 'dl' },
                    dlRow('Đợt', cls.period.name + (cls.period.status === 'CLOSED' ? ' (đợt đã đóng)' : '')),
                    dlRow('Ngày kiểm tra', formatDate(cls.examDate)),
                    dlRow('Địa điểm', cls.location),
                    dlRow('Giám khảo', teachers),
                    dlRow('Ghi chú', cls.note))),
            pendingSlot,
            h('section', { class: 'card' },
                h('h2', null, me.role === 'ADMIN' && !cls.access.manage
                    ? `Người học thuộc đơn vị bạn quản lý (${cls.participantCount})`
                    : `Người học (${cls.participantCount})`),
                participantsSlot));
        drawParticipants();
        pendingRequests().then((card) => replace(pendingSlot, card));
    }

    draw();
    if (cls.access.edit && currentQuery().get('edit') === '1') openClassModal({ cls, onSaved: reload });
}
