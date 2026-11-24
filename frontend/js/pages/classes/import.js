import { backLink } from '../../ui/back-link.js';
import { replace } from '../../ui/escape.js';
import { createImporter } from '../../ui/importer.js';
import { pageHead } from '../../ui/page-head.js';

/** Nhập nhiều lớp kèm người học (Super Admin). Đợt kiểm tra chọn ngay trong file, theo từng lớp. */
export function render(container) {
    const importer = createImporter({
        templateUrl: '/api/v1/classes/import-template',
        uploadPath: (dryRun) => `/classes/import?dryRun=${dryRun}`,
        actionLabels: { CREATE: 'Tạo nội dung kiểm tra' },
        doneText: (r) => `Đã tạo ${r.counts.CREATE || 0} nội dung kiểm tra. Gán giám khảo ở trang của từng nội dung kiểm tra.`,
    });
    replace(container,
        backLink('#/classes', 'Nội dung kiểm tra'),
        pageHead('Nhập nội dung kiểm tra bằng Excel',
            'Tạo nhiều nội dung kiểm tra kèm danh sách người học. Đợt kiểm tra và người học chọn ngay trong file mẫu.'),
        importer.element);
}
