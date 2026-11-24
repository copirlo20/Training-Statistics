import { api } from '../../core/api.js';
import { formatDateTime } from '../../core/format.js';
import { formatScore, GRADE, PARTICIPANT_STATE, parseScore, previewGrade } from '../../core/refdata.js';
import { h, replace } from '../../ui/escape.js';
import { createForm } from '../../ui/form.js';
import { openModal } from '../../ui/modal.js';
import { toast } from '../../ui/toast.js';

/** Phần dùng chung cho điểm: hiển thị điểm và xếp loại, xem trước xếp loại, hộp thoại sửa điểm của Super Admin. */

/** Thẻ xếp loại, luôn có chữ kèm màu. */
export function gradeBadge(grade) {
    const g = GRADE[grade] || { label: grade, badge: 'badge' };
    return h('span', { class: g.badge }, g.label);
}

/** Điểm và xếp loại đứng cạnh nhau, ví dụ "7.5 Khá". */
export function scoreWithGrade(x10, grade) {
    return h('span', { class: 'score-grade' },
        h('span', { class: 'score' }, formatScore(x10)), gradeBadge(grade));
}

/** Thẻ tình trạng của người học (Đã có điểm, Không kiểm tra, Chưa kiểm tra). */
export function stateBadge(state) {
    const s = PARTICIPANT_STATE[state] || { label: state, badge: 'badge' };
    return h('span', { class: s.badge }, s.label);
}

/**
 * Ô kết quả của một lần tham gia: điểm và xếp loại (kèm "Đã sửa"), hoặc tình trạng kèm lý do vắng.
 * p: { state, result?, absence? } như phần tử của GET /classes/{id}/participants hoặc /users/{id}/history.
 */
export function stateCell(p) {
    if (p.result) {
        return h('span', { class: 'state-line' }, scoreWithGrade(p.result.scoreX10, p.result.grade),
            p.result.edited ? h('span', { class: 'cell-sub' }, 'Đã sửa') : null);
    }
    const note = p.absence ? [p.absence.reasonName, p.absence.note].filter(Boolean).join(': ') : '';
    return h('span', { class: 'state-line' }, stateBadge(p.state),
        note ? h('span', { class: 'cell-sub state-note', title: note }, note) : null);
}

/**
 * Xem trước xếp loại dưới ô nhập điểm, cập nhật theo từng phím gõ. Trả hàm đọc giá trị hiện tại.
 * @returns {() => {x10?: number, value?: string, error?: string}}
 */
function attachPreview(input, target) {
    const update = () => {
        const raw = input.value.trim();
        if (raw === '') {
            replace(target, h('span', { class: 'text-muted' }, 'Xếp loại hiện khi nhập điểm.'));
            return;
        }
        const s = parseScore(raw);
        replace(target, s.error
            ? h('span', { class: 'text-danger' }, s.error)
            : ['Sẽ ghi: ', scoreWithGrade(s.x10, previewGrade(s.x10))]);
    };
    input.addEventListener('input', update);
    update();
    return () => parseScore(input.value);
}

/** Danh sách các lần sửa điểm, mới nhất trước. */
async function loadHistory(resultId, slot) {
    replace(slot, h('p', { class: 'loading' }, 'Đang tải...'));
    try {
        const items = await api(`/results/${resultId}/history`);
        replace(slot, items.length === 0
            ? h('p', { class: 'text-muted' }, 'Chưa sửa lần nào.')
            : h('ul', { class: 'pick-list' }, items.map((e) => h('li', { class: 'pick-row' },
                h('div', { class: 'pick-info' },
                    h('span', { class: 'pick-title' }, h('span', { class: 'score' }, formatScore(e.oldScoreX10)), ' → ',
                        h('span', { class: 'score' }, formatScore(e.newScoreX10))),
                    h('span', { class: 'cell-sub' }, e.reason),
                    h('span', { class: 'cell-sub' }, `${e.changedByName} · ${formatDateTime(e.changedAt)}`))))));
    } catch (e) {
        replace(slot, h('p', { class: 'text-danger' }, e.message));
    }
}

/** Super Admin sửa điểm: điểm mới, lý do bắt buộc, xác nhận, xem lịch sử sửa. */
export function openEditResult(p, onSaved) {
    const r = p.result;
    let modal;
    const preview = h('div', { class: 'score-preview', 'aria-live': 'polite' });
    const form = createForm({
        fields: [
            { name: 'score', label: 'Điểm mới (0 đến 10)', required: true, requiredMessage: 'Nhập điểm mới.',
                inputmode: 'decimal', autocomplete: 'off',
                validate: (v) => {
                    const s = parseScore(v);
                    if (s.error) return s.error;
                    return s.x10 === r.scoreX10 ? 'Điểm mới giống điểm hiện tại.' : null;
                } },
            { name: 'reason', label: 'Lý do sửa', type: 'textarea', required: true, requiredMessage: 'Nhập lý do sửa điểm.',
                maxLength: 500 },
        ],
        submitLabel: 'Sửa điểm',
        onSubmit: async (v) => {
            // Không mở thêm hộp thoại xác nhận chồng lên: dòng "Sẽ ghi" dưới ô điểm đã cho thấy điểm và xếp loại.
            const s = parseScore(v.score);
            const saved = await api(`/results/${r.id}`, { method: 'PUT',
                body: { score: Number(s.value), reason: v.reason, version: r.version } });
            modal.close();
            toast(`Đã sửa điểm ${p.user.fullName}: ${formatScore(saved.result.scoreX10)} (${GRADE[saved.result.grade]?.label || saved.result.grade}).`,
                { type: 'success' });
            onSaved(saved);
        },
    });
    const scoreInput = form.element.querySelector('input[name="score"]');
    scoreInput.closest('.field').append(preview);
    attachPreview(scoreInput, preview);
    const historySlot = h('div');
    modal = openModal({ title: 'Sửa điểm', body: [
        h('dl', { class: 'dl' },
            h('dt', null, 'Người học'), h('dd', null, p.user.fullName),
            h('dt', null, 'Điểm hiện tại'), h('dd', null, scoreWithGrade(r.scoreX10, r.grade)),
            h('dt', null, 'Nhập bởi'), h('dd', null, `${r.gradedByName} · ${formatDateTime(r.gradedAt)}`)),
        form.element,
        h('h3', { class: 'pick-heading' }, 'Lịch sử sửa điểm'),
        historySlot] });
    loadHistory(r.id, historySlot);
}
