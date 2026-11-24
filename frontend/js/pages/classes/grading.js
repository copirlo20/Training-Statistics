import { api, ApiError } from '../../core/api.js';
import { currentUser } from '../../core/auth.js';
import { searchKey } from '../../core/format.js';
import { formatScore, OPEN_STATUS, parseScore, previewGrade, snapshotUnitChoices } from '../../core/refdata.js';
import { backLink } from '../../ui/back-link.js';
import { h, replace } from '../../ui/escape.js';
import { filterField } from '../../ui/filter-field.js';
import { icon } from '../../ui/icons.js';
import { failureReport, openModal } from '../../ui/modal.js';
import { multiSelect } from '../../ui/multi-select.js';
import { pageHead } from '../../ui/page-head.js';
import { toast } from '../../ui/toast.js';
import { gradeBadge, openEditResult, scoreWithGrade } from './results.js';

/**
 * Chấm điểm một nội dung kiểm tra (Super Admin, giám khảo). Danh sách gom theo đơn vị lúc tham gia, mỗi người một hàng
 * thấp ("họ tên · mã" một dòng), 2 đến 3 người một hàng trên màn rộng; lọc theo tên, mã, nhiều đơn vị; gõ điểm rồi Enter
 * để sang người kế tiếp. Một nút "Lưu điểm" chung lưu mọi điểm vừa nhập, có bước xác
 * nhận liệt kê tên, điểm, xếp loại (R22). Người đã có điểm thì khóa; chỉ Super Admin sửa được.
 */
export async function render(container, params) {
    const me = currentUser();
    const cls = await api(`/classes/${params.id}`);
    if (!cls.access.manage) {
        replace(container, h('div', { class: 'state-page' },
            h('h1', null, 'Không chấm điểm được nội dung kiểm tra này'),
            h('p', { class: 'text-muted' }, 'Chỉ Super Admin và giám khảo của nội dung kiểm tra được chấm điểm.'),
            h('a', { class: 'btn btn-secondary', href: `#/classes/${cls.id}` }, 'Về nội dung kiểm tra')));
        return;
    }

    let items = [];
    let filter = 'TODO';
    let query = '';
    /** Mã đơn vị lúc tham gia đang lọc (gồm đơn vị con); rỗng là mọi đơn vị. */
    let units = [];
    /** Điểm đã gõ nhưng chưa lưu, giữ khi lọc, tìm: participantId -> chuỗi gõ. */
    const drafts = new Map();
    const justSaved = new Set();

    const progressText = h('span', { class: 'grade-progress-text' });
    const progressFill = h('span', { class: 'grade-progress-fill' });
    const progress = h('div', { class: 'grade-progress', 'aria-live': 'polite' }, progressText,
        h('span', { class: 'grade-progress-track', 'aria-hidden': 'true' }, progressFill));
    const listSlot = h('div');
    const barText = h('span', { class: 'grade-bar-text', 'aria-live': 'polite' });
    const saveBtn = h('button', { type: 'button', class: 'btn' }, 'Lưu điểm');
    const clearBtn = h('button', { type: 'button', class: 'btn btn-secondary' }, 'Xóa điểm vừa nhập');
    const bar = h('div', { class: 'grade-bar', role: 'region', 'aria-label': 'Lưu điểm' }, barText, h('div', { class: 'btn-row' }, clearBtn, saveBtn));

    const isSelf = (p) => p.user.kind === 'PERSON' && p.user.id === me.id;

    async function load() {
        replace(listSlot, h('p', { class: 'loading' }, 'Đang tải...'));
        try {
            items = await api(`/classes/${cls.id}/participants`);
            for (const id of [...drafts.keys()]) {
                const p = items.find((x) => x.id === id);
                if (!p || p.result) drafts.delete(id);
            }
            unitSel.setOptions(snapshotUnitChoices(items));
            units = unitSel.values();
            drawList();
        } catch (e) {
            replace(listSlot, h('div', { class: 'alert alert-error' }, h('p', null, e.message)));
        }
    }

    function updateProgress() {
        const done = items.filter((p) => p.result).length;
        replace(progressText, `Đã có điểm ${done}/${items.length} người`);
        progressFill.style.width = `${items.length ? Math.round((done * 100) / items.length) : 0}%`;
        progress.classList.toggle('is-complete', items.length > 0 && done === items.length);
    }

    /** Các điểm đã gõ, kèm kết quả đọc (điểm x10 hoặc lỗi). */
    function entered() {
        return [...drafts.entries()].filter(([, raw]) => raw.trim() !== '')
            .map(([id, raw]) => ({ p: items.find((x) => x.id === id), raw, s: parseScore(raw) }))
            .filter((e) => e.p);
    }

    function drawBar() {
        const list = entered();
        const bad = list.filter((e) => e.s.error).length;
        saveBtn.disabled = list.length === 0;
        clearBtn.disabled = list.length === 0;
        replace(barText, list.length === 0 ? 'Chưa nhập điểm mới.'
            : `Đã nhập ${list.length} điểm chưa lưu${bad ? `, ${bad} điểm sai cần sửa` : ''}.`);
    }

    function preview(slot, raw) {
        if (raw.trim() === '') {
            replace(slot, null);
            return null;
        }
        const s = parseScore(raw);
        replace(slot, s.error ? h('span', { class: 'text-danger grade-error' }, s.error) : gradeBadge(previewGrade(s.x10)));
        return s;
    }

    function entry(p) {
        const inputId = `score-${p.id}`;
        const slot = h('span', { class: 'grade-preview', id: `${inputId}-preview`, 'aria-live': 'polite' });
        const input = h('input', {
            id: inputId, class: 'input grade-input', type: 'text', inputmode: 'decimal', autocomplete: 'off',
            enterkeyhint: 'next', placeholder: '0-10', 'aria-describedby': slot.id, 'data-pid': String(p.id),
            'aria-label': `Điểm của ${p.user.fullName}`,
        });
        input.value = drafts.get(p.id) || '';
        const s0 = preview(slot, input.value);
        if (s0?.error) input.setAttribute('aria-invalid', 'true');
        input.addEventListener('input', () => {
            drafts.set(p.id, input.value);
            const s = preview(slot, input.value);
            input.setAttribute('aria-invalid', s?.error ? 'true' : 'false');
            drawBar();
        });
        // Enter sang ô kế tiếp để nhập nhanh cả danh sách bằng bàn phím số.
        input.addEventListener('keydown', (e) => {
            if (e.key !== 'Enter') return;
            e.preventDefault();
            const all = [...listSlot.querySelectorAll('.grade-input')];
            const next = all[all.indexOf(input) + 1];
            if (next) next.focus();
            else saveBtn.focus();
        });
        return h('div', { class: 'grade-entry' }, input, slot);
    }

    // Một hàng một người: "họ tên · mã định danh" trên một dòng (cắt bằng "…" nếu dài), cột điểm và xếp loại cố định
    // bề rộng để các hàng thẳng nhau.
    function row(p) {
        let right;
        if (p.result) {
            right = h('div', { class: 'grade-entry' }, scoreWithGrade(p.result.scoreX10, p.result.grade),
                me.role === 'SUPER_ADMIN'
                    ? h('button', { type: 'button', class: 'icon-btn', 'aria-label': `Sửa điểm của ${p.user.fullName}`, title: 'Sửa điểm',
                        onclick: () => openEditResult(p, onEdited) }, icon('edit', { small: true }))
                    : h('span', { class: 'grade-lock', title: 'Đã khóa. Cần sửa thì báo Super Admin.' }, icon('lock', { small: true })));
        } else if (isSelf(p)) {
            right = h('span', { class: 'cell-sub grade-self' }, 'Không tự chấm cho mình');
        } else {
            right = entry(p);
        }
        const name = [p.user.rankSymbol, p.user.fullName].filter(Boolean).join(' ');
        const code = p.user.kind === 'UNIT' ? 'Cả đơn vị' : p.user.personalCode;
        return h('li', { class: ['grade-row', p.result ? 'is-done' : null, justSaved.has(p.id) ? 'is-saved' : null] },
            h('div', { class: 'grade-who', title: [name, code].filter(Boolean).join(' · ') },
                h('span', { class: 'grade-line' },
                    h('span', { class: 'grade-name' }, name),
                    code ? h('span', { class: ['grade-code', p.user.kind === 'UNIT' ? null : 'mono'] }, code) : null),
                p.absence && !p.result ? h('span', { class: 'grade-absent' }, `Không kiểm tra: ${p.absence.reasonName}`) : null),
            right);
    }

    function onEdited(saved) {
        items = items.map((x) => (x.id === saved.id ? saved : x));
        drawList();
    }

    // Gom theo đơn vị lúc tham gia, theo thứ tự mã đơn vị; đầu nhóm ghi số đã có điểm của cả nhóm.
    function groupBlock(list) {
        const unit = list[0].snapshotUnit;
        const all = items.filter((p) => p.snapshotUnit.code === unit.code);
        const done = all.filter((p) => p.result).length;
        return h('section', { class: 'grade-group', 'aria-label': unit.name },
            h('div', { class: 'grade-group-head' },
                h('h2', null, unit.name),
                h('span', { class: ['badge', done === all.length ? 'badge-success' : 'badge-warning'] },
                    `${done}/${all.length} đã có điểm`)),
            h('ul', { class: 'grade-grid' }, list.map(row)));
    }

    function drawList() {
        updateProgress();
        drawBar();
        const key = searchKey(query);
        const shown = items.filter((p) => (filter === 'ALL' || !p.result || justSaved.has(p.id))
            && (units.length === 0 || units.some((u) => p.snapshotUnit.code === u || p.snapshotUnit.code.startsWith(`${u}.`)))
            && (!key || searchKey(`${p.user.fullName} ${p.user.personalCode || ''}`).includes(key)));
        const groups = new Map();
        for (const p of [...shown].sort((a, b) => a.snapshotUnit.code.localeCompare(b.snapshotUnit.code))) {
            if (!groups.has(p.snapshotUnit.code)) groups.set(p.snapshotUnit.code, []);
            groups.get(p.snapshotUnit.code).push(p);
        }
        replace(listSlot, shown.length === 0
            ? h('p', { class: 'text-muted pick-empty' }, items.length === 0 ? 'Nội dung kiểm tra chưa có người học nào.'
                : key || units.length ? 'Không có người học khớp bộ lọc.' : 'Mọi người học đã có điểm.')
            : [...groups.values()].map(groupBlock));
    }

    clearBtn.addEventListener('click', () => {
        drafts.clear();
        drawList();
    });

    saveBtn.addEventListener('click', () => {
        const list = entered();
        const bad = list.filter((e) => e.s.error);
        if (bad.length) {
            toast(`Có ${bad.length} điểm không hợp lệ. Sửa các ô được đánh dấu đỏ trước khi lưu.`, { type: 'error' });
            listSlot.querySelector(`.grade-input[data-pid="${bad[0].p.id}"]`)?.focus();
            return;
        }
        openModal({
            title: `Xác nhận lưu ${list.length} điểm`,
            size: 'wide',
            body: [
                h('p', { class: 'field-hint' }, 'Mỗi người chỉ nhập điểm một lần. Sau khi lưu, chỉ Super Admin sửa được.'),
                h('div', { class: 'table-scroll' }, h('table', { class: 'grade-confirm' },
                    h('thead', null, h('tr', null, h('th', { scope: 'col' }, 'Người học'), h('th', { scope: 'col' }, 'Đơn vị'),
                        h('th', { scope: 'col' }, 'Điểm và xếp loại'))),
                    h('tbody', null, list.map((e) => h('tr', null,
                        h('td', null, e.p.user.fullName, e.p.user.personalCode ? h('span', { class: 'cell-sub mono' }, e.p.user.personalCode) : null),
                        h('td', null, e.p.snapshotUnit.name),
                        h('td', null, scoreWithGrade(e.s.x10, previewGrade(e.s.x10))))))))],
            actions: [
                { label: 'Nhập lại', variant: 'secondary' },
                { label: `Lưu ${list.length} điểm`, onClick: (close) => { close(); saveAll(list); } },
            ],
        });
    });

    async function saveAll(list) {
        saveBtn.disabled = true;
        const failed = [];
        let conflict = false;
        let saved = 0;
        for (const e of list) {
            try {
                const r = await api(`/classes/${cls.id}/participants/${e.p.id}/result`, { method: 'POST', body: { score: Number(e.s.value) } });
                items = items.map((x) => (x.id === r.id ? r : x));
                drafts.delete(e.p.id);
                justSaved.add(r.id);
                saved++;
            } catch (err) {
                failed.push({ who: e.p.user.fullName, message: err.message });
                if (err instanceof ApiError && err.status === 409) conflict = true;
            }
        }
        if (saved) toast(`Đã lưu ${saved} điểm.`, { type: 'success' });
        if (failed.length) {
            failureReport(`${failed.length} điểm chưa lưu được`, 'Các điểm này vẫn còn trong ô nhập để bạn xem lại.', failed);
        }
        // Người khác vừa chấm hoặc đã gỡ khỏi nội dung (409): tải lại để thấy tình trạng thật.
        if (conflict) await load();
        else drawList();
    }

    const search = h('input', { id: 'grade-search', class: 'input', type: 'search', autocomplete: 'off',
        placeholder: 'Tên hoặc mã định danh' });
    search.addEventListener('input', () => { query = search.value; drawList(); });
    const unitSel = multiSelect({ id: 'grade-unit', allLabel: 'Tất cả đơn vị', unit: 'đơn vị',
        onChange: (v) => { units = v; drawList(); } });
    const segmented = h('div', { class: 'segmented', role: 'radiogroup', 'aria-label': 'Người hiển thị' });
    const drawSegmented = () => replace(segmented, [['TODO', 'Chưa có điểm'], ['ALL', 'Tất cả']].map(([value, label]) =>
        h('button', { type: 'button', class: 'btn btn-secondary', role: 'radio', 'aria-checked': String(filter === value),
            'aria-pressed': String(filter === value),
            onclick: () => { filter = value; justSaved.clear(); drawSegmented(); drawList(); } }, label)));
    drawSegmented();

    const st = OPEN_STATUS[cls.status] || { label: cls.status, badge: 'badge' };
    replace(container,
        backLink(`#/classes/${cls.id}`, cls.name),
        pageHead('Chấm điểm',
            [h('span', { class: st.badge }, st.label), ` ${cls.name} · ${cls.period.name}`],
            [h('a', { class: 'btn btn-secondary', href: `#/classes/${cls.id}/import-results` }, 'Nhập điểm Excel'),
                h('a', { class: 'btn btn-secondary', href: `/api/v1/classes/${cls.id}/results/export`, download: '' },
                    'Xuất điểm Excel')]),
        h('section', { class: 'card grade-tools' },
            h('div', { class: 'toolbar toolbar-dense grade-toolbar' },
                filterField('grade-search', 'Tìm người học', search),
                filterField('grade-unit', 'Đơn vị', unitSel.element),
                h('div', { class: 'field field-action' }, h('span', { class: 'field-caption' }, 'Hiển thị'), segmented)),
            progress,
            h('p', { class: 'field-hint' }, `Gõ điểm từ 0 đến 10 (tối đa một chữ số thập phân, ví dụ ${formatScore(65)}), `
                + 'Enter để sang người kế tiếp, rồi bấm Lưu điểm. Mỗi người chỉ nhập một lần; sau khi lưu chỉ Super Admin sửa được.')),
        listSlot,
        bar);
    await load();
}
