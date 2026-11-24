import { api } from '../../core/api.js';
import { currentUser } from '../../core/auth.js';
import { formatDate } from '../../core/format.js';
import { CATEGORY, formatScore, GRADE, GRADE_ORDER } from '../../core/refdata.js';
import { readQuery, toQueryString } from '../../core/router.js';
import { backLink } from '../../ui/back-link.js';
import { append } from '../../ui/escape.js';
import { printSheet, printTable, printToolbar } from '../../ui/print-sheet.js';
import { emptyParticipantFilter, participantFilterText, participantMatches } from './participant-filter.js';

/**
 * In kết quả một nội dung kiểm tra (M6.3): cùng tiêu đề, dòng thông tin và cột với file "Xuất điểm Excel"
 * (result/ResultExportService), cấp bậc in bằng ký hiệu như báo cáo danh sách. A4 ngang. Danh sách máy chủ đã lọc
 * theo phạm vi người xem (giám khảo, Super Admin: cả nội dung; Admin: nhánh mình; người học: chính mình). Mở từ trang
 * chi tiết kèm bộ lọc đang chọn (tham số trên địa chỉ): chỉ in người khớp, số liệu tính trên phần được in và bản in
 * ghi rõ bộ lọc.
 */
export async function render(container, params) {
    const me = currentUser();
    const filter = readQuery(emptyParticipantFilter());
    const [cls, all] = await Promise.all([
        api(`/classes/${params.id}`),
        api(`/classes/${params.id}/participants`, { query: { state: 'ACTIVE' } }),
    ]);
    const list = all.filter((p) => participantMatches(p, filter));
    const filterText = participantFilterText(filter, all);

    const tested = list.filter((p) => p.result).length;
    const absent = list.filter((p) => !p.result && p.absence).length;
    const gradeLine = (kind) => {
        const items = list.filter((p) => p.result && (p.user.kind === 'UNIT') === (kind === 'UNIT'));
        if (items.length === 0) return null;
        const counts = GRADE_ORDER.map((g) => `${GRADE[g].label} ${items.filter((p) => p.result.grade === g).length}`);
        // Điểm cá nhân và điểm cả đơn vị không trộn (quy tắc thống kê).
        return `${kind === 'UNIT' ? 'Xếp loại đơn vị' : 'Xếp loại cá nhân'}: ${counts.join('   ·   ')}`;
    };
    const teachers = cls.teachers.length ? cls.teachers.map((t) => t.fullName).join(', ') : 'Super Admin';
    const size = filterText ? `Số người khớp bộ lọc: ${list.length}/${all.length}` : `Sĩ số: ${list.length}`;

    append(container, [
        backLink(`#/classes/${cls.id}${toQueryString(filter)}`, cls.name),
        printToolbar({ landscape: true, allHref: filterText ? `#/classes/${cls.id}/print` : null }),
        printSheet({
            title: `Kết quả kiểm tra: ${cls.name}`,
            landscape: true,
            info: [
                `Nội dung: ${cls.name}   |   Đợt: ${cls.period.name}`,
                `Ngày kiểm tra: ${cls.examDate ? formatDate(cls.examDate) : 'chưa đặt'}`
                    + (cls.location ? `   |   Địa điểm: ${cls.location}` : '') + `   |   Giám khảo: ${teachers}`,
                `${size}   |   Đã kiểm tra: ${tested}   |   Không kiểm tra: ${absent}   |   Chưa kiểm tra: ${list.length - tested - absent}`,
                gradeLine('PERSON'),
                gradeLine('UNIT'),
                filterText,
                `Phạm vi: ${scopeText(cls, me)}`,
            ],
            note: 'Xếp loại: dưới 5.0 Không đạt, 5.0 đến 6.5 Đạt, 6.6 đến 7.9 Khá, từ 8.0 Giỏi. Cấp bậc ghi bằng ký hiệu.',
            children: [printTable({
                caption: `Kết quả kiểm tra ${cls.name}`,
                emptyText: filterText ? 'Không có người học nào khớp bộ lọc.' : 'Nội dung kiểm tra chưa có người học.',
                columns: [
                    { label: 'STT', cls: 'center' },
                    { label: 'Mã định danh', cls: 'nowrap' },
                    { label: 'Họ tên', cls: 'wide' },
                    { label: 'Cấp bậc', cls: 'center' },
                    { label: 'Đối tượng', cls: 'center' },
                    { label: 'Đơn vị', cls: 'wide' },
                    { label: 'Điểm', cls: 'num' },
                    { label: 'Xếp loại', cls: 'nowrap' },
                    { label: 'Tình trạng', cls: 'nowrap' },
                    { label: 'Ghi chú', cls: 'wide' },
                ],
                rows: list.map((p, i) => [
                    String(i + 1),
                    p.user.kind === 'UNIT' ? p.snapshotUnit.code : p.user.personalCode,
                    p.user.fullName,
                    p.user.kind === 'UNIT' ? 'Cả đơn vị' : p.user.rankSymbol,
                    p.user.kind === 'UNIT' ? '' : CATEGORY[p.user.category]?.short,
                    p.snapshotUnit.name,
                    p.result ? formatScore(p.result.scoreX10) : '',
                    p.result ? GRADE[p.result.grade]?.label : '',
                    stateText(p),
                    noteText(p),
                ]),
            })],
        })]);
}

function scopeText(cls, me) {
    if (cls.access.manage) return 'toàn bộ người học của nội dung';
    return me.role === 'ADMIN' ? 'người học thuộc đơn vị bạn quản lý' : 'kết quả của bạn';
}

function stateText(p) {
    if (p.result) return 'Đã kiểm tra';
    if (p.absence) return 'Không kiểm tra';
    return 'Chưa kiểm tra';
}

function noteText(p) {
    if (p.result) return p.result.edited ? 'Điểm đã được Super Admin sửa' : '';
    if (p.absence) return [p.absence.reasonName, p.absence.note].filter(Boolean).join(': ');
    return '';
}
