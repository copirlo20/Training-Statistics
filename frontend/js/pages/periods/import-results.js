import { api } from '../../core/api.js';
import { backLink } from '../../ui/back-link.js';
import { append } from '../../ui/escape.js';
import { pageHead } from '../../ui/page-head.js';
import { periodFromQuery, periodImporter } from '../../ui/period-importer.js';

/**
 * Chấm điểm mọi nội dung kiểm tra của một đợt bằng một file Excel (Super Admin, yêu cầu ngày 2026-11-23). File mẫu như
 * file phân công: mỗi đơn vị lúc tham gia một sheet, cột là các nội dung của đợt, ô là điểm. Đợt đã khóa vẫn chấm được.
 */
export async function render(container) {
    const periods = await api('/periods');
    const periodId = periodFromQuery(periods);

    const head = [
        backLink('#/periods', 'Đợt kiểm tra'),
        pageHead('Chấm điểm cả đợt bằng Excel',
            'Mỗi đơn vị một sheet, cột là các nội dung kiểm tra của đợt (kể cả nội dung đã đóng). '
                + 'Điền điểm vào ô của nội dung người đó tham gia, để trống nếu chưa kiểm tra.'),
    ];
    append(container, [...head, ...periodImporter({
        id: 'pr-period',
        periods,
        periodId,
        emptyText: 'Chưa có đợt kiểm tra nào.',
        optionLabel: (p) => p.name + (p.status === 'OPEN' ? '' : ' (đã đóng)'),
        importer: (id) => ({
            templateUrl: `/api/v1/periods/${id}/results/import-template`,
            uploadPath: (dryRun) => `/periods/${id}/results/import?dryRun=${dryRun}`,
            doneText: (r) => (r.counts.GRADE ? `Đã ghi ${r.counts.GRADE} điểm.` : 'File không có điểm mới nào.'),
            note: 'Người học khớp theo mã định danh, dòng "Cả đơn vị" theo mã đơn vị. Ô đã có điểm hiện sẵn và bị khóa: '
                + 'đổi điểm thì báo lỗi, không ghi đè. Danh sách người học thay đổi sau khi tải thì tải lại file mẫu.',
        }),
    })]);
}
