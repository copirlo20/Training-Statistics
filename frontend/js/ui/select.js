import { h } from './escape.js';

/**
 * Ô chọn. multiple = true thì thành danh sách checkbox (dễ chạm trên điện thoại hơn &lt;select multiple&gt;).
 *
 * @param {{id: string, name: string, options: {value: string, label: string}[], value?: string|string[],
 *          multiple?: boolean, placeholder?: string, required?: boolean, describedBy?: string}} config
 * @returns {{element: HTMLElement, value: () => string|string[], setValue: (v) => void, focus: () => void,
 *            setInvalid: (b: boolean) => void}}
 */
export function createSelect({ id, name, options, value, multiple = false, placeholder, required = false, describedBy }) {
    if (!multiple) {
        const select = h('select', { id, name, class: 'select', required, 'aria-describedby': describedBy },
            placeholder !== undefined ? h('option', { value: '' }, placeholder) : null,
            options.map((o) => h('option', { value: o.value }, o.label)));
        select.value = value ?? '';
        return {
            element: select,
            value: () => select.value,
            setValue: (v) => { select.value = v ?? ''; },
            focus: () => select.focus(),
            setInvalid: (b) => select.setAttribute('aria-invalid', b ? 'true' : 'false'),
        };
    }

    const selected = new Set((value || []).map(String));
    const boxes = options.map((o, i) => {
        const input = h('input', { type: 'checkbox', id: `${id}-${i}`, name, value: o.value, checked: selected.has(String(o.value)) });
        return { input, label: h('label', { class: 'check', for: `${id}-${i}` }, input, h('span', null, o.label)) };
    });
    const list = h('div', { class: 'check-list', id, role: 'group', 'aria-describedby': describedBy },
        boxes.map((b) => b.label));
    return {
        element: list,
        value: () => boxes.filter((b) => b.input.checked).map((b) => b.input.value),
        setValue: (v) => {
            const s = new Set((v || []).map(String));
            boxes.forEach((b) => { b.input.checked = s.has(b.input.value); });
        },
        focus: () => boxes[0]?.input.focus(),
        setInvalid: (b) => list.setAttribute('aria-invalid', b ? 'true' : 'false'),
    };
}
