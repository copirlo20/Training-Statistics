import { api } from '../../core/api.js';
import { currentUser } from '../../core/auth.js';
import { formatDate } from '../../core/format.js';
import { loadUnits, unitChoices } from '../../core/refdata.js';
import { currentQuery, navigate } from '../../core/router.js';
import { backLink } from '../../ui/back-link.js';
import { append, h, replace } from '../../ui/escape.js';
import { confirmDialog } from '../../ui/modal.js';
import { multiSelect } from '../../ui/multi-select.js';
import { pageHead } from '../../ui/page-head.js';
import { toast } from '../../ui/toast.js';

/**
 * Admin tạo đề nghị thêm hoặc gỡ người học (Super Admin và giáo viên thêm trực tiếp ở trang lớp).
 * Đối tượng phải thuộc đơn vị Admin quản lý: người tìm trong phạm vi, đơn vị chọn trong nhánh, người gỡ lấy từ
 * danh sách người học mà máy chủ đã lọc theo nhánh.
 */
export async function render(container) {
    const me = currentUser();
    const [classes, units] = await Promise.all([api('/classes/open'), loadUnits()]);

    const back = backLink('#/requests', 'Đề nghị');
    const head = pageHead('Tạo đề nghị', `Chỉ đề nghị được người, đơn vị thuộc ${me.unit?.name || 'đơn vị bạn quản lý'} và cấp dưới.`);
    if (classes.length === 0) {
        append(container, [back, head, h('div', { class: 'alert alert-info' }, h('p', null, 'Hiện không có nội dung kiểm tra nào đang mở.'))]);
        return;
    }

    const preset = currentQuery().get('classId');
    const classSel = h('select', { id: 'rq-class', class: 'select' },
        h('option', { value: '' }, 'Chọn nội dung kiểm tra đang mở'),
        classes.map((c) => h('option', { value: String(c.id) },
            `${c.name} · ${c.periodName}${c.examDate ? ' · ' + formatDate(c.examDate) : ''}`)));
    if (preset && classes.some((c) => String(c.id) === preset)) classSel.value = preset;

    let type = 'ADD';
    let targetKind = 'PERSON';
    const typeGroup = h('div', { class: 'segmented', role: 'group', 'aria-label': 'Loại đề nghị' });
    const kindGroup = h('div', { class: 'segmented', role: 'group', 'aria-label': 'Đối tượng' });
    const area = h('div');

    const segment = (el, items, current, onPick) => replace(el, items.map(([value, label]) => h('button', {
        type: 'button', class: 'btn btn-secondary', 'aria-pressed': String(current === value), onclick: () => onPick(value),
    }, label)));

    const selectedClass = () => classes.find((c) => String(c.id) === classSel.value);

    async function send(body, label) {
        const cls = selectedClass();
        if (!(await confirmDialog(`Gửi đề nghị ${type === 'ADD' ? 'thêm' : 'gỡ'} ${label} ${type === 'ADD' ? 'vào' : 'khỏi'} nội dung kiểm tra ${cls.name}?`,
            { title: 'Gửi đề nghị', okLabel: 'Gửi đề nghị' }))) return;
        try {
            const r = await api(`/classes/${cls.id}/requests`, { method: 'POST', body: { type, ...body } });
            toast('Đã gửi đề nghị. Giám khảo của nội dung kiểm tra sẽ nhận được thông báo.', { type: 'success' });
            navigate(`/requests/${r.id}`);
        } catch (e) {
            toast(e.message, { type: 'error' });
        }
    }

    async function sendMany(chosen) {
        const cls = selectedClass();
        if (!(await confirmDialog(`Gửi ${chosen.length} đề nghị thêm cả đơn vị (${chosen.map((u) => u.name).join(', ')}) vào nội dung kiểm tra ${cls.name}?`,
            { title: 'Gửi đề nghị', okLabel: `Gửi ${chosen.length} đề nghị` }))) return;
        const failed = [];
        for (const u of chosen) {
            try {
                await api(`/classes/${cls.id}/requests`, { method: 'POST', body: { type, unitId: u.id } });
            } catch (e) {
                failed.push(`${u.name}: ${e.message}`);
            }
        }
        const sent = chosen.length - failed.length;
        if (sent > 0) toast(`Đã gửi ${sent} đề nghị. Giám khảo của nội dung kiểm tra sẽ nhận được thông báo.`, { type: 'success' });
        if (failed.length) toast(`Không gửi được ${failed.length} đề nghị. ${failed.join(' · ')}`, { type: 'error' });
        if (failed.length === 0) navigate('/requests');
    }

    const pick = (title, sub, label, onClick) => h('li', { class: 'pick-row' },
        h('div', { class: 'pick-info' }, h('span', { class: 'pick-title' }, title), sub ? h('span', { class: 'cell-sub' }, sub) : null),
        h('div', { class: 'pick-action' }, h('button', { type: 'button', class: 'btn btn-secondary', onclick: onClick }, label)));

    function personSearch() {
        const results = h('ul', { class: 'pick-list', 'aria-live': 'polite' });
        const input = h('input', { id: 'rq-q', class: 'input', type: 'search', placeholder: 'Họ tên hoặc mã định danh', autocomplete: 'off' });
        const run = async (e) => {
            e?.preventDefault();
            const q = input.value.trim();
            if (q.length < 2) {
                replace(results, h('li', { class: 'text-muted pick-empty' }, 'Gõ ít nhất 2 ký tự.'));
                return;
            }
            replace(results, h('li', { class: 'loading' }, 'Đang tìm...'));
            try {
                const res = await api('/users', { query: { q, status: 'ACTIVE', size: 20 } });
                replace(results, res.items.length === 0
                    ? h('li', { class: 'text-muted pick-empty' }, 'Không tìm thấy người nào trong đơn vị bạn quản lý.')
                    : res.items.map((u) => pick(u.fullName, [u.rankSymbol, u.unitName, u.personalCode].filter(Boolean).join(' · '),
                        'Đề nghị thêm', () => send({ userId: u.id }, u.fullName))));
            } catch (err) {
                replace(results, h('li', { class: 'pick-empty' }, err.message));
            }
        };
        return [h('form', { class: 'search-row', role: 'search', onsubmit: run },
            h('div', { class: 'field' }, h('label', { for: 'rq-q' }, 'Tìm người'), input),
            h('button', { type: 'submit', class: 'btn' }, 'Tìm')), results];
    }

    function unitPicker() {
        // Chọn được nhiều đơn vị: mỗi đơn vị là một đề nghị riêng, gửi một lần.
        const sel = multiSelect({ id: 'rq-unit', allLabel: 'Chọn đơn vị', unit: 'đơn vị',
            options: unitChoices(units, { onlyActive: true, onlyMine: true }) });
        return h('form', { class: 'search-row', onsubmit: (e) => {
            e.preventDefault();
            const chosen = sel.values().map((v) => units.find((x) => String(x.id) === v)).filter(Boolean);
            if (chosen.length === 0) { toast('Chọn ít nhất một đơn vị.', { type: 'error' }); return; }
            if (chosen.length === 1) send({ unitId: chosen[0].id }, `cả đơn vị ${chosen[0].name}`);
            else sendMany(chosen);
        } },
        h('div', { class: 'field' }, h('label', { for: 'rq-unit' }, 'Đơn vị (cả đơn vị là một đối tượng kiểm tra)'), sel.element),
        h('button', { type: 'submit', class: 'btn' }, 'Đề nghị thêm'));
    }

    async function participantPicker() {
        const cls = selectedClass();
        const list = h('ul', { class: 'pick-list' }, h('li', { class: 'loading' }, 'Đang tải...'));
        api(`/classes/${cls.id}/participants`).then((items) => {
            replace(list, items.length === 0
                ? h('li', { class: 'text-muted pick-empty' }, 'Nội dung kiểm tra chưa có người nào thuộc đơn vị bạn quản lý.')
                : items.map((p) => pick(p.user.fullName, p.user.kind === 'UNIT' ? 'Cả đơn vị' : [p.user.rankSymbol, p.user.unitName].filter(Boolean).join(' · '),
                    'Đề nghị gỡ', () => send(p.user.kind === 'UNIT' ? { unitId: p.snapshotUnit.id } : { userId: p.user.id }, p.user.fullName))));
        }).catch((e) => replace(list, h('li', { class: 'pick-empty' }, e.message)));
        return [h('p', { class: 'text-muted' }, 'Người học trong nội dung kiểm tra thuộc đơn vị bạn quản lý:'), list];
    }

    async function draw() {
        segment(typeGroup, [['ADD', 'Thêm vào nội dung kiểm tra'], ['REMOVE', 'Gỡ khỏi nội dung kiểm tra']], type, (v) => { type = v; draw(); });
        segment(kindGroup, [['PERSON', 'Một người'], ['UNIT', 'Cả đơn vị']], targetKind, (v) => { targetKind = v; draw(); });
        if (!selectedClass()) {
            replace(area, h('p', { class: 'text-muted' }, 'Chọn nội dung kiểm tra trước.'));
            return;
        }
        if (type === 'REMOVE') replace(area, await participantPicker());
        else replace(area, kindGroup, targetKind === 'PERSON' ? personSearch() : unitPicker());
    }
    classSel.addEventListener('change', draw);

    append(container, [
        back,
        head,
        h('section', { class: 'card' },
            h('div', { class: 'field' }, h('label', { for: 'rq-class' }, 'Nội dung kiểm tra'), classSel),
            h('h2', { class: 'pick-heading' }, 'Loại đề nghị'), typeGroup,
            area)]);
    await draw();
}
