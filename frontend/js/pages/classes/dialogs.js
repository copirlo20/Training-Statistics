import { api } from '../../core/api.js';
import { catalogOptions } from '../../core/refdata.js';
import { h, replace } from '../../ui/escape.js';
import { createForm } from '../../ui/form.js';
import { openModal } from '../../ui/modal.js';
import { toast } from '../../ui/toast.js';

/** Hộp thoại dùng ở trang chi tiết lớp. Mọi quyền đều do máy chủ kiểm lại. */

const MIN_QUERY = 2;

/** Ô tìm kiếm có nút Tìm, tự tìm khi ngừng gõ. onSearch(q) được gọi khi đủ ký tự. */
function searchBox(id, label, placeholder, onSearch) {
    const input = h('input', { id, class: 'input', type: 'search', placeholder, autocomplete: 'off' });
    let timer;
    const run = () => {
        const q = input.value.trim();
        if (q.length >= MIN_QUERY) onSearch(q);
    };
    input.addEventListener('input', () => {
        clearTimeout(timer);
        timer = setTimeout(run, 350);
    });
    const form = h('form', { class: 'search-row', role: 'search', onsubmit: (e) => { e.preventDefault(); run(); } },
        h('div', { class: 'field' }, h('label', { for: id }, label), input),
        h('button', { type: 'submit', class: 'btn' }, 'Tìm'));
    return { element: form, input };
}

/** Một dòng kết quả: tên, dòng phụ, và nút hoặc chữ trạng thái bên phải. */
function pickRow(title, sub, action) {
    return h('li', { class: 'pick-row' },
        h('div', { class: 'pick-info' }, h('span', { class: 'pick-title' }, title), sub ? h('span', { class: 'cell-sub' }, sub) : null),
        action);
}

const personSub = (p) => [p.rankSymbol, p.unitName, p.personalCode].filter(Boolean).join(' · ');

/**
 * Tìm ứng viên trên toàn Lữ đoàn (chỉ tên, cấp bậc, đơn vị, mã) và thêm trực tiếp vào lớp.
 * @param {'PERSON'|'UNIT'} kind
 */
export function openCandidates(cls, kind, onChanged) {
    const isUnit = kind === 'UNIT';
    const results = h('ul', { class: 'pick-list', 'aria-live': 'polite' },
        h('li', { class: 'text-muted pick-empty' }, `Gõ ít nhất ${MIN_QUERY} ký tự để tìm.`));
    let changed = false;

    async function add(item, button) {
        button.disabled = true;
        try {
            await api(`/classes/${cls.id}/participants`, { method: 'POST',
                body: isUnit ? { unitId: item.unitId } : { userId: item.id } });
            changed = true;
            replace(button.closest('.pick-action'), h('span', { class: 'badge badge-success' }, 'Đã thêm'));
            toast(`Đã thêm ${isUnit ? item.name : item.fullName} vào nội dung kiểm tra.`, { type: 'success' });
        } catch (e) {
            button.disabled = false;
            toast(e.message, { type: 'error' });
        }
    }

    async function search(q) {
        replace(results, h('li', { class: 'loading' }, 'Đang tìm...'));
        try {
            const list = await api(`/classes/${cls.id}/candidates`, { query: { q, kind } });
            if (list.length === 0) {
                replace(results, h('li', { class: 'text-muted pick-empty' }, 'Không tìm thấy.'));
                return;
            }
            replace(results, list.map((item) => {
                const action = h('div', { class: 'pick-action' });
                if (item.inClass) {
                    replace(action, h('span', { class: 'badge' }, 'Đã tham gia'));
                } else {
                    const b = h('button', { type: 'button', class: 'btn btn-secondary' }, 'Thêm');
                    b.addEventListener('click', () => add(item, b));
                    replace(action, b);
                }
                return isUnit
                    ? pickRow(item.name, `${item.path} · ${item.code}`, action)
                    : pickRow(item.fullName, personSub(item), action);
            }), list.length >= 20 ? h('li', { class: 'text-muted pick-empty' }, 'Chỉ hiện 20 kết quả đầu. Gõ cụ thể hơn để thu hẹp.') : null);
        } catch (e) {
            replace(results, h('li', { class: 'pick-empty' }, e.message));
        }
    }

    const box = searchBox('cand-q', isUnit ? 'Tên hoặc mã đơn vị' : 'Họ tên hoặc mã định danh',
        isUnit ? 'Ví dụ: tham muu' : 'Ví dụ: nguyen van', search);
    openModal({
        title: isUnit ? 'Thêm đơn vị vào nội dung kiểm tra' : 'Thêm người vào nội dung kiểm tra',
        body: [
            h('p', { class: 'text-muted' }, isUnit
                ? 'Cả đơn vị là một đối tượng kiểm tra; điểm đơn vị thống kê riêng, không trộn với điểm cá nhân.'
                : 'Tìm trên toàn Lữ đoàn. Chỉ hiện tên, cấp bậc, đơn vị và mã định danh.'),
            box.element, results],
        onClose: () => { if (changed) onChanged(); },
    });
}

/** Gán giáo viên (Super Admin): tìm người có tài khoản đang hoạt động, chọn, lưu cả danh sách. */
export function openTeachers(cls, onSaved) {
    const chosen = new Map(cls.teachers.map((t) => [t.id, t]));
    const chosenList = h('ul', { class: 'pick-list' });
    const results = h('ul', { class: 'pick-list', 'aria-live': 'polite' });
    let modal;

    function drawChosen() {
        replace(chosenList, chosen.size === 0
            ? h('li', { class: 'text-muted pick-empty' }, 'Chưa gán ai. Super Admin luôn quản lý được nội dung kiểm tra.')
            : [...chosen.values()].map((t) => pickRow(t.fullName, personSub(t), h('div', { class: 'pick-action' },
                h('button', { type: 'button', class: 'btn btn-secondary', onclick: () => { chosen.delete(t.id); drawChosen(); } }, 'Bỏ')))));
    }

    async function search(q) {
        replace(results, h('li', { class: 'loading' }, 'Đang tìm...'));
        try {
            const res = await api('/users', { query: { q, status: 'ACTIVE', size: 20 } });
            const eligible = res.items.filter((u) => u.username);
            replace(results, eligible.length === 0
                ? h('li', { class: 'text-muted pick-empty' }, 'Không có người có tài khoản nào khớp.')
                : eligible.map((u) => {
                    const t = { id: u.id, fullName: u.fullName, rankSymbol: u.rankSymbol, unitName: u.unitName };
                    const action = h('div', { class: 'pick-action' });
                    if (chosen.has(u.id)) replace(action, h('span', { class: 'badge' }, 'Đã chọn'));
                    else {
                        replace(action, h('button', { type: 'button', class: 'btn btn-secondary', onclick: () => {
                            chosen.set(u.id, t);
                            drawChosen();
                            replace(action, h('span', { class: 'badge' }, 'Đã chọn'));
                        } }, 'Chọn'));
                    }
                    return pickRow(u.fullName, personSub({ ...t, personalCode: u.username }), action);
                }));
        } catch (e) {
            replace(results, h('li', { class: 'pick-empty' }, e.message));
        }
    }

    const save = async () => {
        try {
            await api(`/classes/${cls.id}/teachers`, { method: 'PUT', body: { userIds: [...chosen.keys()] } });
            modal.close();
            toast('Đã lưu danh sách giám khảo.', { type: 'success' });
            onSaved();
        } catch (e) {
            toast(e.message, { type: 'error' });
        }
    };

    drawChosen();
    const box = searchBox('teacher-q', 'Tìm người có tài khoản', 'Họ tên, mã định danh, tài khoản', search);
    modal = openModal({
        title: 'Gán giám khảo',
        body: [
            h('h3', { class: 'pick-heading' }, 'Giám khảo của nội dung kiểm tra'), chosenList,
            h('h3', { class: 'pick-heading' }, 'Thêm giám khảo'),
            h('p', { class: 'text-muted' }, 'Chỉ người có tài khoản đăng nhập và đang hoạt động.'),
            box.element, results],
        actions: [{ label: 'Hủy', variant: 'secondary' }, { label: 'Lưu danh sách', onClick: save }],
    });
}

/** Ghi hoặc xóa lý do không kiểm tra. */
export function openAbsence(cls, p, cat, onSaved) {
    let modal;
    const send = async (body) => {
        await api(`/classes/${cls.id}/participants/${p.id}/absence`, { method: 'PUT', body });
        modal.close();
        toast(body.reasonId ? 'Đã ghi lý do không kiểm tra.' : 'Đã xóa lý do không kiểm tra.', { type: 'success' });
        onSaved();
    };
    const form = createForm({
        fields: [
            { name: 'reasonId', label: 'Lý do', type: 'select', required: true, placeholder: 'Chọn lý do', requiredMessage: 'Chọn lý do.',
                options: catalogOptions(cat.absenceReasons, { currentId: p.absence?.reasonId }),
                value: p.absence ? String(p.absence.reasonId) : '' },
            { name: 'note', label: 'Ghi chú', type: 'textarea', maxLength: 500, value: p.absence?.note },
        ],
        submitLabel: 'Lưu lý do',
        extraActions: p.absence ? [h('button', { type: 'button', class: 'btn btn-secondary', onclick: async () => {
            try { await send({ reasonId: null }); } catch (e) { toast(e.message, { type: 'error' }); }
        } }, 'Xóa lý do')] : [],
        onSubmit: (v) => send({ reasonId: Number(v.reasonId), note: v.note || null }),
    });
    modal = openModal({ title: `Lý do không kiểm tra: ${p.user.fullName}`, body: form.element });
}

/** Gỡ khỏi lớp. Người đã có điểm: chỉ Super Admin, bắt buộc lý do, điểm giữ lại (máy chủ kiểm). */
export function openRemove(cls, p, onSaved) {
    let modal;
    const hasResult = !!p.result;
    const form = createForm({
        fields: [{ name: 'reason', label: hasResult ? 'Lý do gỡ' : 'Lý do gỡ (không bắt buộc)', type: 'textarea',
            required: hasResult, requiredMessage: 'Người này đã có điểm, phải nhập lý do gỡ.', maxLength: 500 }],
        submitLabel: 'Gỡ khỏi nội dung kiểm tra',
        onSubmit: async (v) => {
            await api(`/classes/${cls.id}/participants/${p.id}/remove`, { method: 'POST', body: { reason: v.reason || null } });
            modal.close();
            toast(`Đã gỡ ${p.user.fullName} khỏi nội dung kiểm tra.`, { type: 'success' });
            onSaved();
        },
    });
    modal = openModal({ title: 'Gỡ khỏi nội dung kiểm tra', body: [
        h('p', null, 'Gỡ ', h('strong', null, p.user.fullName), ' khỏi nội dung kiểm tra ', h('strong', null, cls.name), '?'),
        hasResult ? h('div', { class: 'alert alert-warning' },
            h('p', null, 'Người này đã có điểm. Điểm vẫn được giữ trong hệ thống nhưng không tính vào thống kê.')) : null,
        form.element] });
}
