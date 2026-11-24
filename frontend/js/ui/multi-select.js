import { searchKey } from '../core/format.js';
import { h, replace } from './escape.js';
import { icon } from './icons.js';
import { createPopover } from './popover.js';

let counter = 0;

/**
 * Ô lọc chọn nhiều giá trị: nút hiện tóm tắt ("Tất cả đơn vị", tên mục, hoặc "3 đơn vị"), bấm mở khung có ô tìm
 * và danh sách ô đánh dấu. Không chọn gì nghĩa là không lọc. Mở bằng chạm hoặc bàn phím, không phụ thuộc hover.
 *
 * @param {{id: string, options: {value: string, label: string, sub?: string, indent?: boolean}[], values?: string[],
 *          allLabel?: string, unit?: string, searchable?: boolean, onChange?: (values: string[]) => void}} o
 * @returns {{element: HTMLElement, values: () => string[], setValues: (v: string[]) => void,
 *            setOptions: (opts: object[]) => void}}
 */
export function multiSelect({ id, options = [], values = [], allLabel = 'Tất cả', unit = 'mục', searchable = true, onChange }) {
    let opts = options;
    const selected = new Set(values.map(String));
    const listId = `ms-${++counter}`;
    const summary = h('span', { class: 'multi-summary' });
    const trigger = h('button', { type: 'button', id, class: 'multi-trigger' }, summary, icon('chevron-down', { small: true }));
    const search = searchable
        ? h('input', { type: 'search', class: 'input multi-search', placeholder: 'Tìm trong danh sách', autocomplete: 'off',
            'aria-label': 'Tìm trong danh sách', 'aria-controls': listId })
        : null;
    const list = h('ul', { class: 'multi-list', id: listId });
    const clearBtn = h('button', { type: 'button', class: 'btn btn-secondary' }, 'Bỏ chọn');
    const doneBtn = h('button', { type: 'button', class: 'btn' }, 'Xong');
    const panel = h('div', { class: 'multi-panel' }, search, list, h('div', { class: 'multi-foot' }, clearBtn, doneBtn));

    let timer;
    const changed = () => {
        drawSummary();
        clearTimeout(timer);
        timer = setTimeout(() => onChange?.([...selected]), 250);
    };

    function drawSummary() {
        const chosen = opts.filter((o) => selected.has(String(o.value)));
        const text = chosen.length === 0 ? allLabel : chosen.length === 1 ? chosen[0].label : `${chosen.length} ${unit}`;
        replace(summary, text);
        trigger.setAttribute('aria-label', `${allLabel.replace(/^Tất cả /, '')}: ${chosen.length === 0 ? 'chưa chọn, đang xem tất cả' : chosen.map((o) => o.label).join(', ')}`);
        trigger.classList.toggle('is-active', chosen.length > 0);
    }

    function drawList() {
        const key = searchKey(search?.value);
        const shown = opts.filter((o) => !key || searchKey(`${o.label} ${o.sub || ''} ${o.keywords || ''}`).includes(key));
        replace(list, shown.length === 0 ? h('li', { class: 'multi-empty' }, 'Không có mục nào khớp.')
            : shown.map((o, i) => {
                const boxId = `${listId}-${i}`;
                const input = h('input', { type: 'checkbox', id: boxId, value: String(o.value), checked: selected.has(String(o.value)) });
                input.addEventListener('change', () => {
                    if (input.checked) selected.add(String(o.value));
                    else selected.delete(String(o.value));
                    changed();
                });
                return h('li', null, h('label', { class: ['check', 'multi-option', o.indent ? 'multi-indent' : null], for: boxId },
                    input, h('span', null, o.label, o.sub ? h('span', { class: 'cell-sub' }, o.sub) : null)));
            }));
    }

    search?.addEventListener('input', drawList);
    clearBtn.addEventListener('click', () => {
        if (selected.size === 0) return;
        selected.clear();
        drawList();
        changed();
    });
    let popover;
    doneBtn.addEventListener('click', () => { popover.close(); trigger.focus(); });
    popover = createPopover({ trigger, panel, className: 'multi-select', onOpen: () => { drawList(); search?.focus(); } });

    drawSummary();
    return {
        element: popover.element,
        values: () => [...selected],
        setValues: (v) => {
            selected.clear();
            (v || []).forEach((x) => selected.add(String(x)));
            drawSummary();
            drawList();
        },
        setOptions: (next) => {
            opts = next;
            const valid = new Set(next.map((o) => String(o.value)));
            [...selected].forEach((v) => { if (!valid.has(v)) selected.delete(v); });
            drawSummary();
            drawList();
        },
    };
}
