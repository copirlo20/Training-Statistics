import { api } from './core/api.js';
import { logout } from './core/auth.js';
import { PASSWORD_MIN_LENGTH } from './core/refdata.js';
import { h, replace } from './ui/escape.js';
import { createForm } from './ui/form.js';

/**
 * Trang đăng nhập. Đã đăng nhập rồi thì chuyển thẳng vào ứng dụng, trừ khi tài khoản đang dùng mật khẩu do người khác
 * đặt: khi đó hiện form đặt mật khẩu mới, đổi xong mới vào ứng dụng.
 */

function nextTarget() {
    const next = new URLSearchParams(location.search).get('next');
    // Chỉ chấp nhận đường dẫn nội bộ dạng #/..., không cho chuyển hướng ra ngoài.
    return next && /^#\/[\w\-/]*$/.test(next) ? next : '#/';
}

function goApp() {
    location.replace('index.html' + nextTarget());
}

async function main() {
    const body = document.getElementById('login-body');
    try {
        const me = await api('/me', { allow401: true });
        if (me.mustChangePassword) showChangePassword(body, null);
        else goApp();
        return;
    } catch (e) {
        if (e?.status !== 401) {
            replace(body, h('div', { class: 'alert alert-error', role: 'alert' }, h('p', null, e?.message || 'Không kết nối được máy chủ.')));
            return;
        }
    }

    const form = createForm({
        fields: [
            { name: 'username', label: 'Tài khoản', required: true, requiredMessage: 'Nhập tài khoản.',
                autocomplete: 'username', maxLength: 50 },
            { name: 'password', label: 'Mật khẩu', type: 'password', required: true, requiredMessage: 'Nhập mật khẩu.',
                autocomplete: 'current-password' },
        ],
        submitLabel: 'Đăng nhập',
        onSubmit: async (values) => {
            const me = await api('/auth/login', { method: 'POST', body: values, allow401: true });
            if (me.mustChangePassword) showChangePassword(body, values.password);
            else goApp();
        },
    });
    form.element.querySelector('.form-actions .btn').classList.add('btn-block');
    replace(body, form.element,
        h('p', { class: 'field-hint' }, 'Quên mật khẩu: liên hệ quản trị viên đơn vị để được đặt lại.'));
    form.focusFirst();
}

/** @param {string|null} currentPassword mật khẩu vừa gõ khi đăng nhập; null thì hỏi lại (đã có phiên từ trước). */
function showChangePassword(body, currentPassword) {
    const fields = [
        { name: 'newPassword', label: 'Mật khẩu mới', type: 'password', required: true, minLength: PASSWORD_MIN_LENGTH,
            requiredMessage: 'Nhập mật khẩu mới.', autocomplete: 'new-password',
            hint: `Tối thiểu ${PASSWORD_MIN_LENGTH} ký tự, khác mật khẩu hiện tại.` },
        { name: 'confirmPassword', label: 'Nhập lại mật khẩu mới', type: 'password', required: true,
            requiredMessage: 'Nhập lại mật khẩu mới để xác nhận.', autocomplete: 'new-password',
            validate: (v, all) => (v !== all.newPassword ? 'Hai lần nhập mật khẩu mới không khớp.' : null) },
    ];
    if (currentPassword === null) {
        fields.unshift({ name: 'currentPassword', label: 'Mật khẩu hiện tại', type: 'password', required: true,
            requiredMessage: 'Nhập mật khẩu hiện tại.', autocomplete: 'current-password' });
    }
    const form = createForm({
        fields,
        submitLabel: 'Đổi mật khẩu và tiếp tục',
        onSubmit: async (v) => {
            await api('/me/password', { method: 'PUT',
                body: { currentPassword: currentPassword ?? v.currentPassword, newPassword: v.newPassword } });
            goApp();
        },
        extraActions: [h('button', { type: 'button', class: 'btn btn-secondary', onclick: () => logout() }, 'Đăng xuất')],
    });
    replace(body,
        h('div', { class: 'alert alert-info', role: 'status' },
            h('p', null, 'Mật khẩu của tài khoản này do quản trị viên đặt. Đặt mật khẩu mới của riêng bạn trước khi tiếp tục.')),
        form.element);
    form.focusFirst();
}

main();
