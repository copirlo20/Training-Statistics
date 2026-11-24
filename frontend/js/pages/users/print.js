import { api } from '../../core/api.js';
import { formatDate } from '../../core/format.js';
import { CATEGORY, formatScore, GRADE, PARTICIPANT_STATE } from '../../core/refdata.js';
import { readQuery, toQueryString } from '../../core/router.js';
import { backLink } from '../../ui/back-link.js';
import { append } from '../../ui/escape.js';
import { printFacts, printSection, printSheet, printTable, printToolbar } from '../../ui/print-sheet.js';
import { emptyHistoryFilter, historyFilterText, historyMatches } from './history.js';

/**
 * Trang in thông tin cá nhân (thiết kế lại 2026-11-22, cùng bố cục với file Excel): mục I thông tin chung (cấp bậc in
 * tên đầy đủ, đơn vị in đủ đường dẫn), mục II kết quả kiểm tra qua các đợt. A4 dọc. Mở từ thẻ lịch sử điểm kèm bộ lọc
 * đang chọn (tham số trên địa chỉ): mục II chỉ in nội dung khớp, số liệu tính trên phần được in, bản in ghi rõ bộ lọc.
 */
export async function render(container, params) {
    const filter = readQuery(emptyHistoryFilter());
    const [u, all] = await Promise.all([
        api(`/users/${params.id}/print`),
        api(`/users/${params.id}/history`).catch(() => []),
    ]);
    const history = all.filter((c) => historyMatches(c, filter));
    const filterText = historyFilterText(filter);

    const count = filterText ? `Số nội dung khớp bộ lọc: ${history.length}/${all.length}` : `Số nội dung đã tham gia: ${history.length}`;
    const graded = history.filter((c) => c.result);
    const avg = graded.length ? (graded.reduce((s, c) => s + c.result.scoreX10, 0) / graded.length / 10).toFixed(2) : null;
    const stateText = (c) => {
        if (c.result) return c.result.edited ? 'Đã kiểm tra (điểm đã sửa)' : 'Đã kiểm tra';
        if (c.absence) return [PARTICIPANT_STATE.ABSENT.label, [c.absence.reasonName, c.absence.note].filter(Boolean).join(': ')].join(' - ');
        return PARTICIPANT_STATE[c.state]?.label || c.state;
    };

    append(container, [
        backLink(`#/users/${u.id}${toQueryString(filter)}`, 'Thông tin người dùng'),
        printToolbar({ allHref: filterText ? `#/users/${u.id}/print` : null }),
        printSheet({
            title: `Thông tin cá nhân: ${u.fullName}`,
            info: [
                `Đơn vị: ${u.unit?.path || 'Không thuộc đơn vị'}   |   Mã định danh: ${u.personalCode || ''}`,
                `${count}   |   Đã có điểm: ${graded.length}` + (avg ? `   |   Điểm trung bình: ${avg}` : ''),
                filterText,
            ],
            note: 'Thông tin lấy từ hệ thống tại thời điểm in. Xếp loại: dưới 5.0 Không đạt, 5.0 đến 6.5 Đạt, 6.6 đến 7.9 Khá, từ 8.0 Giỏi.',
            children: [
                printSection('I. Thông tin chung'),
                printFacts([
                    ['Họ và tên', u.fullName],
                    ['Mã định danh', u.personalCode],
                    ['Cấp bậc', u.rank?.name],
                    ['Chức vụ', u.position?.name],
                    ['Đối tượng', CATEGORY[u.category]?.label],
                    ['Ngày sinh', formatDate(u.birthDate)],
                    ['Đơn vị', u.unit?.path || 'Không thuộc đơn vị'],
                    ['Số điện thoại', u.phone],
                    ['Chuyên ngành', u.specialties.map((s) => s.name).join(', ')],
                ]),
                printSection('II. Kết quả kiểm tra'),
                printTable({
                    caption: 'Kết quả kiểm tra qua các đợt',
                    emptyText: filterText ? 'Không có nội dung nào khớp bộ lọc.' : 'Chưa tham gia nội dung kiểm tra nào.',
                    columns: [
                        { label: 'STT', cls: 'center' },
                        { label: 'Đợt kiểm tra' },
                        { label: 'Nội dung kiểm tra', cls: 'wide' },
                        { label: 'Ngày kiểm tra', cls: 'nowrap' },
                        { label: 'Điểm', cls: 'num' },
                        { label: 'Xếp loại', cls: 'nowrap' },
                        { label: 'Tình trạng', cls: 'wide' },
                    ],
                    rows: history.map((c, i) => [
                        String(i + 1),
                        c.classInfo.periodName,
                        c.classInfo.name,
                        formatDate(c.classInfo.examDate),
                        c.result ? formatScore(c.result.scoreX10) : '',
                        c.result ? GRADE[c.result.grade]?.label || c.result.grade : '',
                        stateText(c),
                    ]),
                }),
            ],
        })]);
}
