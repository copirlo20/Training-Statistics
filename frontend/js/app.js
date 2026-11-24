import { loadMe } from './core/auth.js';
import { route, start } from './core/router.js';
import { h, replace } from './ui/escape.js';
import { renderHeader, renderNav } from './ui/nav.js';

/** Khởi động ứng dụng: nạp người đang đăng nhập (chưa đăng nhập thì api.js chuyển về login.html), dựng khung, chạy router. */

route('/', { title: 'Trang chủ', load: () => import('./pages/home.js') });
route('/me', { title: 'Hồ sơ của tôi', load: () => import('./pages/me/profile.js') });
route('/me/password', { title: 'Đổi mật khẩu', load: () => import('./pages/me/password.js') });
route('/admin/audit', { title: 'Nhật ký thao tác', roles: ['SUPER_ADMIN'], load: () => import('./pages/admin/audit.js') });
route('/admin/units', { title: 'Đơn vị', roles: ['SUPER_ADMIN'], load: () => import('./pages/admin/units.js') });
route('/admin/system', { title: 'Hệ thống', roles: ['SUPER_ADMIN'], load: () => import('./pages/admin/system.js') });
route('/admin/catalogs', { title: 'Danh mục', roles: ['SUPER_ADMIN'], load: () => import('./pages/admin/catalogs.js') });
// '/users/new' phải khai báo trước '/users/:id', nếu không "new" bị hiểu là id.
route('/users', { title: 'Người dùng', roles: ['ADMIN', 'SUPER_ADMIN'], load: () => import('./pages/users/list.js') });
route('/users/import', { title: 'Nhập người dùng', roles: ['ADMIN', 'SUPER_ADMIN'], load: () => import('./pages/users/import.js') });
route('/users/new', { title: 'Thêm người dùng', roles: ['ADMIN', 'SUPER_ADMIN'], load: () => import('./pages/users/form.js') });
route('/users/:id/edit', { title: 'Sửa người dùng', roles: ['ADMIN', 'SUPER_ADMIN'], load: () => import('./pages/users/form.js') });
route('/users/:id/print', { title: 'In thông tin cá nhân', load: () => import('./pages/users/print.js') });
route('/users/:id', { title: 'Người dùng', load: () => import('./pages/users/detail.js') });
route('/stats', { title: 'Thống kê', roles: ['ADMIN', 'SUPER_ADMIN'], load: () => import('./pages/stats/index.js') });
route('/stats/class/:id', { title: 'Thống kê nội dung kiểm tra', load: () => import('./pages/stats/class.js') });
route('/notifications', { title: 'Thông báo', load: () => import('./pages/notifications.js') });
route('/periods', { title: 'Đợt kiểm tra', roles: ['SUPER_ADMIN'], load: () => import('./pages/periods/list.js') });
route('/periods/import-results', { title: 'Chấm điểm cả đợt', roles: ['SUPER_ADMIN'], load: () => import('./pages/periods/import-results.js') });
// '/classes/import', '/requests/new' phải khai báo trước route có :id.
route('/classes', { title: 'Nội dung kiểm tra', load: () => import('./pages/classes/list.js') });
route('/classes/import', { title: 'Nhập nội dung kiểm tra', roles: ['SUPER_ADMIN'], load: () => import('./pages/classes/import.js') });
route('/classes/:id/import-results', { title: 'Nhập điểm', load: () => import('./pages/classes/import-results.js') });
route('/classes/:id/print', { title: 'In kết quả kiểm tra', load: () => import('./pages/classes/print.js') });
route('/classes/:id/grade', { title: 'Chấm điểm', load: () => import('./pages/classes/grading.js') });
route('/classes/:id', { title: 'Nội dung kiểm tra', load: () => import('./pages/classes/detail.js') });
route('/assign', { title: 'Phân công người học', load: () => import('./pages/assign/index.js') });
route('/assign/import', { title: 'Phân công bằng Excel', load: () => import('./pages/assign/import.js') });
route('/requests', { title: 'Đề nghị', load: () => import('./pages/requests/list.js') });
route('/requests/new', { title: 'Tạo đề nghị', roles: ['ADMIN'], load: () => import('./pages/requests/new.js') });
route('/requests/:id', { title: 'Đề nghị', load: () => import('./pages/requests/detail.js') });

async function main() {
    const main = document.getElementById('main');
    try {
        await loadMe();
    } catch (e) {
        if (e?.status === 401) return;   // đang chuyển sang trang đăng nhập
        replace(main, h('div', { class: 'state-page' },
            h('h1', null, 'Không kết nối được máy chủ'),
            h('p', { class: 'text-muted' }, e?.message || 'Vui lòng thử lại.'),
            h('button', { type: 'button', class: 'btn', onclick: () => location.reload() }, 'Tải lại')));
        return;
    }
    const header = document.getElementById('app-header');
    const sideNav = document.getElementById('app-nav');
    const tabbar = document.getElementById('tabbar');
    renderHeader(header);
    await start(main, (path) => renderNav(sideNav, tabbar, path));
}

main();
