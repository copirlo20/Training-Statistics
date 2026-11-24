import { api } from '../../core/api.js';
import { PASSWORD_MIN_LENGTH } from '../../core/refdata.js';
import { h } from '../../ui/escape.js';
import { createForm } from '../../ui/form.js';
import { pageHead } from '../../ui/page-head.js';
import { toast } from '../../ui/toast.js';

/** Đổi mật khẩu của chính mình. Máy chủ kiểm tra lại toàn bộ, ở đây chỉ báo lỗi sớm. */
export function render(container) {
    const form = createForm({
        columns: 1,
        fields: [
            { name: 'currentPassword', label: 'Mật khẩu hiện tại', type: 'password', required: true,
                requiredMessage: 'Nhập mật khẩu hiện tại.', autocomplete: 'current-password' },
            { name: 'newPassword', label: 'Mật khẩu mới', type: 'password', required: true, minLength: PASSWORD_MIN_LENGTH,
                requiredMessage: 'Nhập mật khẩu mới.',
                autocomplete: 'new-password', hint: `Tối thiểu ${PASSWORD_MIN_LENGTH} ký tự.` },
            { name: 'confirmPassword', label: 'Nhập lại mật khẩu mới', type: 'password', required: true,
                requiredMessage: 'Nhập lại mật khẩu mới để xác nhận.',
                autocomplete: 'new-password',
                validate: (v, all) => (v !== all.newPassword ? 'Hai lần nhập mật khẩu mới không khớp.' : null) },
        ],
        submitLabel: 'Đổi mật khẩu',
        onSubmit: async (v) => {
            await api('/me/password', { method: 'PUT', body: { currentPassword: v.currentPassword, newPassword: v.newPassword } });
            form.reset();
            toast('Đã đổi mật khẩu.', { type: 'success' });
        },
    });

    container.classList.add('page-narrow');
    container.append(
        pageHead('Đổi mật khẩu'),
        h('section', { class: 'card' }, form.element));
}
