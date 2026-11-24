import { searchKey } from '../../core/format.js';
import { CATEGORY, GRADE, labelsOf, PARTICIPANT_STATE } from '../../core/refdata.js';
import { filterLine } from '../../ui/print-sheet.js';

/**
 * Bộ lọc người học của một nội dung kiểm tra, dùng chung cho trang chi tiết (lọc danh sách) và trang in kết quả (in
 * đúng phần đang lọc). Lọc ở trình duyệt vì danh sách một nội dung đã tải đủ (máy chủ đã giới hạn theo phạm vi).
 */
export function emptyParticipantFilter() {
    return { q: '', units: [], states: [], grades: [], categories: [] };
}

/** Đối tượng để lọc: mã đối tượng của người, UNIT với dòng cả đơn vị, NONE khi người chưa có đối tượng. */
export function categoryOf(p) {
    if (p.user.kind === 'UNIT') return 'UNIT';
    return p.user.category || 'NONE';
}

/** Các giá trị đối tượng theo thứ tự hiển thị. */
export const CATEGORY_VALUES = [...Object.keys(CATEGORY), 'UNIT', 'NONE'];

export function categoryLabel(value) {
    if (value === 'UNIT') return 'Cả đơn vị';
    if (value === 'NONE') return 'Chưa xác định';
    const c = CATEGORY[value];
    return c ? `${c.short} - ${c.label}` : value;
}

/** Đơn vị lọc gồm cả đơn vị con (so theo mã có dấu chấm, như quy tắc nhánh ở máy chủ). */
export function participantMatches(p, f) {
    const code = p.snapshotUnit.code;
    if (f.units.length && !f.units.some((u) => code === u || code.startsWith(`${u}.`))) return false;
    if (f.states.length && !f.states.includes(p.state)) return false;
    if (f.grades.length && !(p.result && f.grades.includes(p.result.grade))) return false;
    if (f.categories.length && !f.categories.includes(categoryOf(p))) return false;
    const key = searchKey(f.q);
    return !key || searchKey([p.user.fullName, p.user.personalCode, p.snapshotUnit.name, p.user.unitName].join(' ')).includes(key);
}

/** Dòng "Bộ lọc: ..." cho bản in; tên đơn vị lấy từ danh sách người học. Null khi không lọc. */
export function participantFilterText(f, participants) {
    const unitName = new Map(participants.map((p) => [p.snapshotUnit.code, p.snapshotUnit.name]));
    return filterLine([
        ['Đơn vị lúc tham gia', f.units.map((u) => unitName.get(u) || u)],
        ['Tình trạng', labelsOf(PARTICIPANT_STATE, f.states)],
        ['Xếp loại', labelsOf(GRADE, f.grades)],
        ['Đối tượng', f.categories.map(categoryLabel)],
        ['Tìm', f.q ? [`"${f.q}"`] : []],
    ]);
}
