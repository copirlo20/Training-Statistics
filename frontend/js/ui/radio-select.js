import { searchKey } from '../core/format.js';
import { h, replace } from './escape.js';
import { icon } from './icons.js';
import { createPopover } from './popover.js';

let counter = 0;

/**
 * Ô chọn MỘT giá trị dạng danh sách radio có ô tìm nhanh (dùng thay &lt;select&gt; khi chọn đơn vị).
 * Mặc định là nút mở khung thả xuống. inline = true thì ô tìm và danh sách nằm sẵn (dùng trong hộp thoại, nơi khung
 * thả xuống bị cuộn theo thân hộp thoại).
 *
 * Chạm hoặc bấm một mục thì chọn và đóng khung. Phím mũi tên chỉ đổi mục, Enter hoặc Esc mới đóng; onChange gọi khi
 * khung đóng mà giá trị đã đổi (inline: gọi ngay), nên duyệt bằng phím không làm trang tải lại nhiều lần.
 *
 * option: { value, label, sub?, indent?, summary?, keywords? }. summary là chữ trên nút khi đã chọn (mặc định label),
 * keywords là chữ thêm để tìm (ví dụ tên đơn vị cha).
 * emptyLabel: có thì thêm mục giá trị rỗng đứng đầu (ô lọc "Toàn Lữ đoàn"); không có thì giá trị rỗng hiện placeholder.
 *
 * @returns {{element: HTMLElement, value: () => string, setValue: (v: string) => void, setOptions: (o: object[]) => void,
 *            focus: () => void, setInvalid: (b: boolean) => void, setDisabled: (b: boolean) => void}}
 */
export function radioSelect({ id, options = [], value = '', placeholder = 'Chọn', emptyLabel, inline = false,
    label = 'mục', describedBy, onChange }) {
    let opts = options;
    let selected = String(value ?? '');
    let committed = selected;
    let keyNav = false;
    const listId = `rs-${++counter}`;
    const all = () => (emptyLabel !== undefined ? [{ value: '', label: emptyLabel }, ...opts] : opts);

    const search = h('input', { type: 'search', class: 'input multi-search', id: inline ? id : null, autocomplete: 'off',
        placeholder: `Tìm ${label}`, 'aria-label': `Tìm ${label}`, 'aria-controls': listId,
        'aria-describedby': inline ? describedBy : null });
    const list = h('ul', { class: 'multi-list radio-list', id: listId, role: 'radiogroup', 'aria-label': `Chọn ${label}` });

    const commit = () => {
        if (selected === committed) return;
        committed = selected;
        onChange?.(selected);
    };

    function drawList() {
        const key = searchKey(search.value);
        const shown = all().filter((o) => !key || searchKey(`${o.label} ${o.sub || ''} ${o.keywords || ''}`).includes(key));
        replace(list, shown.length === 0 ? h('li', { class: 'multi-empty' }, `Không có ${label} nào khớp "${search.value.trim()}".`)
            : shown.map((o, i) => {
                const boxId = `${listId}-${i}`;
                const input = h('input', { type: 'radio', name: listId, id: boxId, value: String(o.value),
                    checked: String(o.value) === selected });
                input.addEventListener('change', () => {
                    if (!input.checked) return;
                    selected = String(o.value);
                    drawSummary();
                    if (inline) commit();
                });
                // Trình duyệt phát cả "click" khi đổi radio bằng phím mũi tên: chỉ đóng khi là chạm, bấm hoặc phím cách.
                // "click" chạy trước "change", nên ghi giá trị ngay tại đây rồi mới đóng (đóng là lúc gọi onChange).
                input.addEventListener('click', () => {
                    if (keyNav) return;
                    selected = String(o.value);
                    drawSummary();
                    if (inline) commit();
                    else finish();
                });
                return h('li', null, h('label', { class: ['check', 'multi-option', o.indent ? 'multi-indent' : null], for: boxId },
                    input, h('span', null, o.label, o.sub ? h('span', { class: 'cell-sub' }, o.sub) : null)));
            }));
    }

    list.addEventListener('keydown', (e) => {
        if (e.key.startsWith('Arrow')) {
            keyNav = true;
            setTimeout(() => { keyNav = false; });
        } else if (e.key === 'Enter') {
            e.preventDefault();
            if (inline) commit();
            else finish();
        }
    });
    search.addEventListener('input', drawList);
    search.addEventListener('keydown', (e) => {
        const radios = [...list.querySelectorAll('input[type=radio]')];
        if (e.key === 'ArrowDown' && radios.length) {
            e.preventDefault();
            (radios.find((r) => r.checked) || radios[0]).focus();
        } else if (e.key === 'Enter') {
            // Enter trong ô tìm: chọn mục đầu tiên khớp.
            e.preventDefault();
            if (radios.length === 0) return;
            radios[0].checked = true;
            selected = radios[0].value;
            drawSummary();
            if (inline) commit();
            else finish();
        }
    });

    const current = h('p', { class: 'radio-current', 'aria-live': 'polite' });
    const summary = h('span', { class: 'multi-summary' });
    let trigger = null;
    let popover = null;
    let element;
    if (inline) {
        element = h('div', { class: 'radio-select radio-inline' }, search, list, current);
    } else {
        trigger = h('button', { type: 'button', id, class: 'multi-trigger', 'aria-describedby': describedBy },
            summary, icon('chevron-down', { small: true }));
        const panel = h('div', { class: 'multi-panel' }, search, list);
        popover = createPopover({ trigger, panel, className: 'multi-select radio-select',
            onOpen: () => {
                search.value = '';
                drawList();
                list.querySelector('input:checked')?.scrollIntoView({ block: 'nearest' });
                search.focus();
            },
            onClose: commit });
        element = popover.element;
    }

    function finish() {
        popover.close();
        trigger.focus();
    }

    function drawSummary() {
        const o = all().find((x) => String(x.value) === selected);
        if (inline) {
            replace(current, o && o.value !== '' ? ['Đã chọn: ', h('strong', null, o.summary || o.label)] : `Chưa chọn ${label}.`);
            return;
        }
        replace(summary, o ? (o.summary || o.label) : placeholder);
        trigger.classList.toggle('is-placeholder', !o);
        // Ô lọc (có mục "tất cả") đang lọc thì tô nổi như ô chọn nhiều.
        trigger.classList.toggle('is-active', emptyLabel !== undefined && !!o && o.value !== '');
    }

    drawList();
    drawSummary();
    return {
        element,
        value: () => selected,
        setValue: (v) => {
            selected = String(v ?? '');
            committed = selected;
            drawSummary();
            list.querySelectorAll('input[type=radio]').forEach((r) => { r.checked = r.value === selected; });
        },
        setOptions: (next) => {
            opts = next;
            if (!all().some((o) => String(o.value) === selected)) selected = committed = '';
            drawSummary();
            drawList();
        },
        focus: () => (inline ? search.focus() : trigger.focus()),
        setInvalid: (b) => (inline ? search : trigger).setAttribute('aria-invalid', b ? 'true' : 'false'),
        setDisabled: (b) => { if (trigger) trigger.disabled = b; else search.disabled = b; },
    };
}
