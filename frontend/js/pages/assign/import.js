import { api } from '../../core/api.js';
import { currentUser } from '../../core/auth.js';
import { backLink } from '../../ui/back-link.js';
import { append } from '../../ui/escape.js';
import { pageHead } from '../../ui/page-head.js';
import { periodFromQuery, periodImporter } from '../../ui/period-importer.js';

/**
 * Phân công người học bằng Excel (yêu cầu ngày 2026-11-20): chọn đợt, tải file mẫu (mỗi đơn vị trong phạm vi một sheet,
 * cột là các nội dung đang mở mình phân công được), ghi x rồi nhập. Quyền giống bảng phân công, máy chủ kiểm: Super
 * Admin, giám khảo thêm, gỡ ngay; Admin gửi đề nghị. Ghi tất cả hoặc không ghi gì.
 */
export async function render(container) {
    const periods = await api('/periods');
    const open = periods.filter((p) => p.status === 'OPEN');
    const periodId = periodFromQuery(open);
    const assignHref = (id) => `#/assign${id ? `?periodId=${id}` : ''}`;

    const back = backLink(assignHref(periodId), 'Phân công');
    const head = [
        back,
        pageHead('Phân công bằng Excel',
            'Mỗi đơn vị một sheet (đơn vị nhỏ nhất), cột là các nội dung kiểm tra đang mở của đợt. '
                + 'Ghi x vào ô của nội dung người đó tham gia, để trống là không tham gia. ' + howApplied(currentUser().role)),
    ];
    append(container, [...head, ...periodImporter({
        id: 'ai-period',
        periods: open,
        periodId,
        emptyText: 'Không có đợt kiểm tra nào đang mở.',
        onChange: (id) => back.setAttribute('href', assignHref(id)),
        importer: (id) => ({
            templateUrl: `/api/v1/assignments/template?periodId=${id}`,
            uploadPath: (dryRun) => `/assignments/import?periodId=${id}&dryRun=${dryRun}`,
            actionLabels: { ADD: 'Thêm ngay', REMOVE: 'Gỡ ngay', REQUEST_ADD: 'Gửi đề nghị thêm', REQUEST_REMOVE: 'Gửi đề nghị gỡ' },
            doneText,
            note: 'Người được khớp theo mã định danh. Người đã có điểm không gỡ bằng file; ô đang chờ duyệt bị bỏ qua. '
                + 'Danh sách người hoặc nội dung thay đổi sau khi tải thì tải lại file mẫu.',
        }),
    })]);
}

function howApplied(role) {
    if (role === 'SUPER_ADMIN') return 'Thay đổi có hiệu lực ngay khi ghi.';
    if (role === 'ADMIN') {
        return 'File chỉ có các đơn vị bạn quản lý; mỗi thay đổi được gửi thành đề nghị cho giám khảo hoặc Super Admin duyệt '
            + '(nội dung bạn làm giám khảo thì thêm, gỡ ngay).';
    }
    return 'File có toàn bộ danh sách người, chỉ có cột các nội dung bạn làm giám khảo; thay đổi có hiệu lực ngay.';
}

function doneText({ counts: c }) {
    const parts = [];
    if (c.ADD || c.REMOVE) parts.push(`thêm ${c.ADD || 0}, gỡ ${c.REMOVE || 0}`);
    if (c.REQUEST_ADD || c.REQUEST_REMOVE) parts.push(`gửi ${(c.REQUEST_ADD || 0) + (c.REQUEST_REMOVE || 0)} đề nghị`);
    return parts.length ? `Đã ${parts.join(', ')}.` : 'File không có thay đổi nào so với hiện tại.';
}
