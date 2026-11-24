import { api } from './core/api.js';
import { h, replace } from './ui/escape.js';
import { createForm } from './ui/form.js';

/** Trang đăng nhập. Đã đăng nhập rồi thì chuyển thẳng vào ứng dụng. */

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
        await api('/me', { allow401: true });
        goApp();
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
            await api('/auth/login', { method: 'POST', body: values, allow401: true });
            goApp();
        },
    });
    form.element.querySelector('.form-actions .btn').classList.add('btn-block');
    replace(body, form.element,
        h('p', { class: 'field-hint' }, 'Quên mật khẩu: liên hệ quản trị viên đơn vị để được đặt lại.'));
    form.focusFirst();
}

main();
