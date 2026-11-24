import { DEFAULT_PASSWORD } from '../../core/refdata.js';
import { backLink } from '../../ui/back-link.js';
import { replace } from '../../ui/escape.js';
import { createImporter } from '../../ui/importer.js';
import { pageHead } from '../../ui/page-head.js';

/** Nhập người dùng bằng Excel (Admin trong phạm vi, Super Admin). */
export function render(container) {
    const importer = createImporter({
        templateUrl: '/api/v1/users/import-template',
        uploadPath: (dryRun) => `/users/import?dryRun=${dryRun}`,
        doneText: (r) => `Đã ghi: tạo mới ${r.counts.CREATE || 0} người, cập nhật ${r.counts.UPDATE || 0} người.`,
        note: `Cột Tài khoản và Quyền (Người dùng hoặc Admin) để cấp tài khoản, cấp quyền luôn. Tài khoản mới có mật khẩu ${DEFAULT_PASSWORD}. `
            + 'Quyền để trống thì người mới là Người dùng. Admin bắt buộc có tài khoản.',
    });
    replace(container,
        backLink('#/users', 'Người dùng'),
        pageHead('Nhập người dùng bằng Excel',
            'Tạo mới hoặc cập nhật nhiều người cùng lúc, khớp theo mã định danh. Chỉ nhận đơn vị trong phạm vi bạn quản lý.'),
        importer.element);
}
