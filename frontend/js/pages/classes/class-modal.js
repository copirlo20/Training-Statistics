import { api } from '../../core/api.js';
import { h, replace } from '../../ui/escape.js';
import { createForm } from '../../ui/form.js';
import { icon } from '../../ui/icons.js';
import { openModal } from '../../ui/modal.js';
import { toast } from '../../ui/toast.js';

const MIN_QUERY = 2;
const SUGGESTIONS = 8;

/**
 * Hộp thoại thêm hoặc sửa nội dung kiểm tra (chỉ Super Admin), gán giám khảo ngay trong hộp thoại: gõ tên, mã định
 * danh hoặc tài khoản để hiện gợi ý. Lưu một lần (máy chủ ghi nội dung và giám khảo cùng giao dịch, ARCHITECTURE C15).
 * Đợt đã đóng không nhận nội dung mới, chỉ hiện khi đang sửa nội dung thuộc đợt đó.
 *
 * @param {{cls?: object, presetPeriodId?: string, onSaved: (saved: object) => void}} o
 */
export async function openClassModal({ cls = null, presetPeriodId = '', onSaved }) {
    const editing = Boolean(cls);
    const periods = await api('/periods');
    const periodOptions = periods
        .filter((p) => p.status === 'OPEN' || p.id === cls?.period.id)
        .map((p) => ({ value: String(p.id), label: p.name + (p.status === 'OPEN' ? '' : ' (đã đóng)') }));
    if (periodOptions.length === 0) {
        openModal({
            title: 'Thêm nội dung kiểm tra',
            body: [h('div', { class: 'alert alert-warning' }, h('p', null, 'Chưa có đợt kiểm tra nào đang mở. Tạo đợt trước, rồi thêm nội dung vào đợt.')),
                h('a', { class: 'btn', href: '#/periods' }, 'Đến trang đợt kiểm tra')],
            actions: [{ label: 'Đóng', variant: 'secondary' }],
        });
        return;
    }

    const picker = teacherPicker(cls?.teachers || []);
    let modal;
    const form = createForm({
        columns: 2,
        fields: [
            { name: 'name', label: 'Tên nội dung', required: true, maxLength: 150, value: cls?.name, requiredMessage: 'Nhập tên nội dung.', full: true },
            { name: 'periodId', label: 'Đợt kiểm tra', type: 'select', required: true, placeholder: 'Chọn đợt', options: periodOptions,
                value: cls ? String(cls.period.id) : (presetPeriodId || (periodOptions.length === 1 ? periodOptions[0].value : '')),
                requiredMessage: 'Chọn đợt kiểm tra.' },
            { name: 'examDate', label: 'Ngày kiểm tra', type: 'date', value: cls?.examDate },
            { name: 'location', label: 'Địa điểm', maxLength: 200, value: cls?.location },
            { name: 'note', label: 'Ghi chú', maxLength: 500, value: cls?.note },
            { name: 'teacherIds', label: 'Giám khảo', type: 'custom', full: true, control: picker.control,
                hint: 'Gõ ít nhất 2 ký tự của tên, mã định danh hoặc tài khoản rồi chọn trong gợi ý. Chỉ người có tài khoản đang hoạt động. '
                    + 'Super Admin luôn quản lý được nội dung, không cần gán.' },
        ],
        submitLabel: editing ? 'Lưu thay đổi' : 'Tạo nội dung kiểm tra',
        extraActions: [h('button', { type: 'button', class: 'btn btn-secondary', onclick: () => modal.close() }, 'Hủy')],
        onSubmit: async (v) => {
            const body = { name: v.name, periodId: Number(v.periodId), examDate: v.examDate || null, location: v.location || null,
                note: v.note || null, teacherIds: v.teacherIds };
            const saved = editing
                ? await api(`/classes/${cls.id}`, { method: 'PUT', body: { ...body, version: cls.version } })
                : await api('/classes', { method: 'POST', body });
            modal.close();
            toast(editing ? 'Đã lưu thay đổi.' : 'Đã tạo nội dung kiểm tra.', { type: 'success' });
            onSaved(saved);
        },
    });
    modal = openModal({ title: editing ? 'Sửa nội dung kiểm tra' : 'Thêm nội dung kiểm tra', body: form.element, size: 'wide' });
    form.focusFirst?.();
}

/** Ô chọn nhiều giám khảo: danh sách đã chọn dạng thẻ, ô tìm có gợi ý ngay bên dưới. */
function teacherPicker(initial) {
    const chosen = new Map(initial.map((t) => [t.id, t]));
    const inputId = `teacher-pick-${Math.random().toString(36).slice(2, 8)}`;
    const listId = `${inputId}-list`;
    const chips = h('ul', { class: 'chip-list', 'aria-label': 'Giám khảo đã chọn' });
    const input = h('input', { id: inputId, class: 'input', type: 'search', autocomplete: 'off', role: 'combobox',
        'aria-autocomplete': 'list', 'aria-expanded': 'false', 'aria-controls': listId,
        placeholder: 'Tìm theo tên, mã định danh hoặc tài khoản' });
    const suggest = h('ul', { class: 'suggest-list', id: listId, role: 'listbox', hidden: true });

    function drawChips() {
        replace(chips, chosen.size === 0 ? h('li', { class: 'chip-empty' }, 'Chưa gán giám khảo nào.')
            : [...chosen.values()].map((t) => h('li', { class: 'chip' },
                h('span', null, t.fullName, t.unitName ? h('span', { class: 'chip-sub' }, t.unitName) : null),
                h('button', { type: 'button', class: 'chip-remove', 'aria-label': `Bỏ ${t.fullName}`,
                    onclick: () => { chosen.delete(t.id); drawChips(); input.focus(); } }, icon('close', { small: true })))));
    }

    const close = () => { suggest.hidden = true; input.setAttribute('aria-expanded', 'false'); };
    let timer;
    let seq = 0;
    async function search() {
        const q = input.value.trim();
        if (q.length < MIN_QUERY) {
            close();
            return;
        }
        const my = ++seq;
        try {
            const res = await api('/users', { query: { q, status: 'ACTIVE', size: 20 } });
            if (my !== seq) return;
            const eligible = res.items.filter((u) => u.username && u.kind !== 'UNIT').slice(0, SUGGESTIONS);
            replace(suggest, eligible.length === 0
                ? h('li', { class: 'suggest-empty', role: 'option', 'aria-disabled': 'true' }, 'Không có người có tài khoản nào khớp.')
                : eligible.map((u) => {
                    const t = { id: u.id, fullName: u.fullName, rankSymbol: u.rankSymbol, unitName: u.unitName, personalCode: u.personalCode };
                    const taken = chosen.has(u.id);
                    return h('li', { role: 'option', 'aria-selected': String(taken) },
                        h('button', { type: 'button', class: 'suggest-item', disabled: taken,
                            onclick: () => { chosen.set(u.id, t); drawChips(); input.value = ''; close(); input.focus(); } },
                        h('span', { class: 'suggest-name' }, [u.rankSymbol, u.fullName].filter(Boolean).join(' ')),
                        h('span', { class: 'cell-sub' }, [u.unitName, u.personalCode, u.username].filter(Boolean).join(' · ')),
                        taken ? h('span', { class: 'badge' }, 'Đã chọn') : null));
                }));
            suggest.hidden = false;
            input.setAttribute('aria-expanded', 'true');
        } catch (e) {
            if (my !== seq) return;
            replace(suggest, h('li', { class: 'suggest-empty' }, e.message));
            suggest.hidden = false;
        }
    }
    input.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(search, 250); });
    input.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && !suggest.hidden) {
            e.stopPropagation();
            close();
        } else if (e.key === 'ArrowDown' && !suggest.hidden) {
            e.preventDefault();
            suggest.querySelector('button:not([disabled])')?.focus();
        } else if (e.key === 'Enter') {
            e.preventDefault();
        }
    });
    drawChips();

    const element = h('div', { class: 'teacher-picker' }, chips, h('div', { class: 'suggest-wrap' }, input, suggest));
    return {
        control: {
            element,
            labelFor: inputId,
            get: () => [...chosen.keys()],
            set: () => {},
            focus: () => input.focus(),
            setInvalid: (b) => input.setAttribute('aria-invalid', b ? 'true' : 'false'),
        },
    };
}
