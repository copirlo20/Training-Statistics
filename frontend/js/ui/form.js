import { ApiError } from '../core/api.js';
import { h, replace } from './escape.js';
import { radioSelect } from './radio-select.js';
import { createSelect } from './select.js';

let formCounter = 0;

/**
 * Form có kiểm tra dữ liệu và hiển thị lỗi theo từng trường (của trình duyệt lẫn của máy chủ).
 * Bố cục: một cột trên điện thoại, hai cột từ 600px khi columns = 2 hoặc 4, bốn cột từ 1024px khi columns = 4
 * (field.full để chiếm cả hàng, field.span = 2 để chiếm hai cột).
 *
 * field: { name, label, type = 'text' | 'password' | 'email' | 'date' | 'number' | 'textarea' | 'select' | 'radio-select' | 'checkboxes'
 *          | 'custom', span (2: chiếm hai cột của lưới 4 cột),
 *          required, requiredMessage, minLength, maxLength, min, max, step, inputmode, autocomplete, placeholder, hint, full,
 *          options (select/radio-select/checkboxes), value, validate(value, values) => thông báo lỗi | null,
 *          radio-select: inline, onChange (xem ui/radio-select.js) }
 *
 * onSubmit(values) trả Promise. Nếu ném ApiError có fieldErrors, lỗi được gắn vào đúng ô.
 */
export function createForm({ fields, submitLabel = 'Lưu', columns = 1, onSubmit, extraActions = [] }) {
    const formId = `f${++formCounter}`;
    const controls = new Map();
    const errors = new Map();

    const alertBox = h('div', { role: 'alert', hidden: true });

    const fieldEls = fields.map((f) => {
        const id = `${formId}-${f.name}`;
        const errorId = `${id}-error`;
        const hintId = f.hint ? `${id}-hint` : null;
        const describedBy = [hintId, errorId].filter(Boolean).join(' ');
        const errorEl = h('div', { class: 'field-error', id: errorId });
        errors.set(f.name, errorEl);
        const labelChildren = [f.label, f.required ? h('span', { class: 'req', 'aria-hidden': 'true' }, ' *') : null];

        let control;
        if (f.type === 'custom') {
            // Ô tự dựng (ví dụ chọn giám khảo có gợi ý): f.control có element, get, set, focus, setInvalid, labelFor.
            control = f.control;
        } else if (f.type === 'radio-select') {
            const rs = radioSelect({ id, options: f.options || [], value: f.value, placeholder: f.placeholder, inline: f.inline,
                label: f.itemLabel, describedBy, onChange: f.onChange });
            control = { get: rs.value, set: rs.setValue, focus: rs.focus, setInvalid: rs.setInvalid, element: rs.element };
        } else if (f.type === 'select' || f.type === 'checkboxes') {
            const sel = createSelect({
                id, name: f.name, options: f.options || [], value: f.value, multiple: f.type === 'checkboxes',
                placeholder: f.placeholder, required: f.required, describedBy,
            });
            control = { get: sel.value, set: sel.setValue, focus: sel.focus, setInvalid: sel.setInvalid, element: sel.element };
        } else {
            const isArea = f.type === 'textarea';
            const input = h(isArea ? 'textarea' : 'input', {
                id,
                name: f.name,
                class: isArea ? 'textarea' : 'input',
                type: isArea ? null : (f.type || 'text'),
                required: f.required,
                minlength: f.minLength,
                maxlength: f.maxLength,
                min: f.min,
                max: f.max,
                step: f.step,
                inputmode: f.inputmode,
                autocomplete: f.autocomplete,
                placeholder: f.placeholder,
                'aria-describedby': describedBy,
            });
            if (f.value !== undefined && f.value !== null) input.value = f.value;
            // Khi bàn phím ảo mở, đưa ô đang nhập vào vùng nhìn thấy.
            input.addEventListener('focus', () => setTimeout(() => input.scrollIntoView({ block: 'center', behavior: 'smooth' }), 300));
            control = {
                get: () => input.value,
                set: (v) => { input.value = v ?? ''; },
                focus: () => input.focus(),
                setInvalid: (b) => input.setAttribute('aria-invalid', b ? 'true' : 'false'),
                element: input,
            };
        }
        controls.set(f.name, control);

        const isGroup = f.type === 'checkboxes';
        return h(isGroup ? 'fieldset' : 'div', { class: ['field', f.full ? 'field-full' : null, f.span === 2 ? 'field-span-2' : null] },
            isGroup ? h('legend', null, labelChildren) : h('label', { for: f.type === 'custom' ? f.control.labelFor : id }, labelChildren),
            control.element,
            f.hint ? h('div', { class: 'field-hint', id: hintId }, f.hint) : null,
            errorEl);
    });

    const submitBtn = h('button', { type: 'submit', class: 'btn' }, submitLabel);
    const form = h('form', { novalidate: true, id: formId },
        alertBox,
        h('div', { class: ['form-grid', columns === 2 ? 'cols-2' : null, columns === 4 ? 'cols-2 cols-4' : null] }, fieldEls),
        h('div', { class: 'form-actions' }, submitBtn, extraActions));

    const values = () => {
        const out = {};
        for (const [name, c] of controls) out[name] = c.get();
        return out;
    };

    const clearErrors = () => {
        for (const [name, el] of errors) {
            el.textContent = '';
            controls.get(name).setInvalid(false);
        }
        alertBox.hidden = true;
        alertBox.className = '';
        alertBox.replaceChildren();
    };

    /** @param {{field: string, message: string}[]} list */
    const setErrors = (list) => {
        let first = null;
        for (const { field, message } of list) {
            const el = errors.get(field);
            if (el) {
                el.textContent = message;
                controls.get(field).setInvalid(true);
                first = first || field;
            }
        }
        if (first) controls.get(first).focus();
        return first !== null;
    };

    const showAlert = (message, type = 'error') => {
        alertBox.className = `alert alert-${type}`;
        replace(alertBox, h('p', null, message));
        alertBox.hidden = false;
    };

    const validate = () => {
        const v = values();
        const list = [];
        for (const f of fields) {
            const value = v[f.name];
            const empty = Array.isArray(value) ? value.length === 0 : String(value ?? '').trim() === '';
            let msg = null;
            if (f.required && empty) msg = f.requiredMessage || 'Không được để trống.';
            else if (!empty && f.minLength && String(value).length < f.minLength) msg = `Tối thiểu ${f.minLength} ký tự.`;
            else if (!empty && f.maxLength && String(value).length > f.maxLength) msg = `Tối đa ${f.maxLength} ký tự.`;
            else if (f.validate) msg = f.validate(value, v);
            if (msg) list.push({ field: f.name, message: msg });
        }
        return list;
    };

    let busy = false;
    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        if (busy) return;
        clearErrors();
        const problems = validate();
        if (problems.length > 0) {
            setErrors(problems);
            return;
        }
        busy = true;
        submitBtn.disabled = true;
        const label = submitBtn.textContent;
        submitBtn.textContent = 'Đang xử lý...';
        try {
            await onSubmit(values());
        } catch (err) {
            if (err instanceof ApiError && err.fieldErrors.length > 0 && setErrors(err.fieldErrors)) {
                if (err.fieldErrors.some((fe) => !errors.has(fe.field))) showAlert(err.message);
            } else {
                showAlert(err?.message || 'Đã có lỗi xảy ra.');
            }
        } finally {
            busy = false;
            submitBtn.disabled = false;
            submitBtn.textContent = label;
        }
    });

    return {
        element: form,
        values,
        setErrors,
        showAlert,
        clearErrors,
        reset: () => { form.reset(); clearErrors(); },
        setValues: (v) => { for (const [k, val] of Object.entries(v)) controls.get(k)?.set(val); },
        focusFirst: () => controls.values().next().value?.focus(),
    };
}
