import { api } from './api.js';
import { currentUser } from './auth.js';

/**
 * Dữ liệu tham chiếu dùng chung: danh mục, cây đơn vị, nhãn trạng thái và quyền.
 * Danh mục và cây đơn vị được nhớ trong lúc mở trang; gọi với force = true sau khi sửa để tải lại.
 */

let catalogsPromise = null;
let unitsPromise = null;

export function loadCatalogs(force = false) {
    if (force || !catalogsPromise) catalogsPromise = api('/catalogs').catch((e) => { catalogsPromise = null; throw e; });
    return catalogsPromise;
}

export function loadUnits(force = false) {
    if (force || !unitsPromise) unitsPromise = api('/units/tree').catch((e) => { unitsPromise = null; throw e; });
    return unitsPromise;
}

/** Mã đơn vị nằm trong phạm vi quản lý của người đang đăng nhập (chỉ để hiển thị; máy chủ kiểm lại). */
export function inMyScope(code) {
    const me = currentUser();
    if (!me) return false;
    if (me.role === 'SUPER_ADMIN') return true;
    if (me.role !== 'ADMIN' || !me.unit) return false;
    return code === me.unit.code || code.startsWith(me.unit.code + '.');
}

/**
 * Lựa chọn đơn vị cho ô radio (ui/radio-select.js) theo thứ tự cây: cấp 1 rồi các đơn vị con thụt vào. Đơn vị con
 * ghi "cha / con" trên nút và tìm được theo tên đơn vị cha.
 * @param {{onlyActive?: boolean, onlyMine?: boolean}} options
 */
export function unitChoices(units, { onlyActive = true, onlyMine = false } = {}) {
    const keep = (u) => (!onlyActive || u.active) && (!onlyMine || inMyScope(u.code));
    const out = [];
    for (const root of units.filter((u) => u.level === 1)) {
        const rootKept = keep(root);
        if (rootKept) out.push({ value: String(root.id), label: root.name, sub: root.code });
        for (const c of units.filter((u) => u.parentId === root.id && keep(u))) {
            out.push({ value: String(c.id), label: c.name, sub: c.code, indent: rootKept,
                summary: `${root.name} / ${c.name}`, keywords: root.name });
        }
    }
    return out;
}

/**
 * Lựa chọn đơn vị cho ô lọc người học: các đơn vị lúc tham gia có trong danh sách, theo thứ tự mã; đơn vị con thụt vào
 * khi đơn vị cha cũng có trong danh sách.
 */
export function snapshotUnitChoices(participants) {
    const byCode = new Map();
    for (const p of participants) byCode.set(p.snapshotUnit.code, p.snapshotUnit.name);
    return [...byCode.entries()].sort((a, b) => a[0].localeCompare(b[0])).map(([code, name]) => {
        const parent = code.includes('.') ? code.slice(0, code.lastIndexOf('.')) : null;
        return { value: code, label: name, sub: code, indent: !!parent && byCode.has(parent) };
    });
}

export const STATUS = {
    ACTIVE: { label: 'Hoạt động', badge: 'badge badge-success' },
    INACTIVE: { label: 'Đã khóa', badge: 'badge badge-warning' },
    DELETED: { label: 'Đã xóa', badge: 'badge badge-danger' },
};

/** Trạng thái đợt, lớp. Luôn có chữ kèm màu. */
export const OPEN_STATUS = {
    OPEN: { label: 'Đang mở', badge: 'badge badge-success' },
    CLOSED: { label: 'Đã đóng', badge: 'badge' },
};

/** Tình trạng của người học trong lớp. */
export const PARTICIPANT_STATE = {
    TESTED: { label: 'Đã có điểm', badge: 'badge badge-success' },
    ABSENT: { label: 'Không kiểm tra', badge: 'badge badge-warning' },
    NOT_TESTED: { label: 'Chưa kiểm tra', badge: 'badge' },
};

/** Xếp loại (PRD): <5.0 Không đạt, 5.0–6.5 Đạt, 6.6–7.9 Khá, ≥8.0 Giỏi. Máy chủ tính; xem trước ở previewGrade. */
export const GRADE = {
    FAIL: { label: 'Không đạt', badge: 'badge badge-danger' },
    PASS: { label: 'Đạt', badge: 'badge' },
    GOOD: { label: 'Khá', badge: 'badge badge-success' },
    EXCELLENT: { label: 'Giỏi', badge: 'badge badge-success' },
};

/** Thứ tự xếp loại từ cao xuống thấp (lựa chọn lọc, dòng đếm xếp loại). */
export const GRADE_ORDER = ['EXCELLENT', 'GOOD', 'PASS', 'FAIL'];

/** Nhãn của các mã theo một bảng tra (GRADE, PARTICIPANT_STATE...); mã lạ giữ nguyên. */
export function labelsOf(table, codes) {
    return codes.map((code) => table[code]?.label || code);
}

export const REQUEST_STATUS = {
    PENDING: { label: 'Đang chờ', badge: 'badge badge-warning' },
    APPROVED: { label: 'Đã duyệt', badge: 'badge badge-success' },
    REJECTED: { label: 'Bị từ chối', badge: 'badge badge-danger' },
    CANCELLED: { label: 'Đã hủy', badge: 'badge' },
};

/** Điểm lưu dạng ×10 (65 là 6.5). */
export function formatScore(x10) {
    return x10 === null || x10 === undefined ? '' : (x10 / 10).toFixed(1);
}

/**
 * Đọc điểm người chấm gõ: 0 đến 10, tối đa một chữ số thập phân, nhận cả dấu phẩy (bàn phím số tiếng Việt).
 * Trả { x10, value } hoặc { error }. Máy chủ kiểm lại.
 */
export function parseScore(text) {
    const s = String(text ?? '').trim().replace(',', '.');
    if (s === '') return { error: 'Nhập điểm.' };
    if (!/^\d{1,2}(\.\d0*)?$/.test(s)) {
        return { error: /^\d+\.\d{2,}$/.test(s) ? 'Điểm chỉ có tối đa một chữ số thập phân (ví dụ 6.5).' : 'Điểm phải là số từ 0 đến 10.' };
    }
    const x10 = Math.round(Number(s) * 10);
    if (x10 > 100) return { error: 'Điểm phải từ 0 đến 10.' };
    return { x10, value: (x10 / 10).toFixed(1) };
}

/**
 * Xếp loại XEM TRƯỚC khi chấm (R22), trước khi điểm được ghi. Xếp loại thật là cột tính ở DB: sau khi lưu, giao
 * diện hiển thị xếp loại máy chủ trả về. Ngưỡng phải giống cột `result.grade` (V1__schema.sql); kịch bản
 * "cham-diem-diem-bien" của tools/ui-check.ps1 so xếp loại xem trước với xếp loại máy chủ ở các điểm biên.
 */
export function previewGrade(x10) {
    if (x10 < 50) return 'FAIL';
    if (x10 <= 65) return 'PASS';
    if (x10 <= 79) return 'GOOD';
    return 'EXCELLENT';
}

/**
 * Đối tượng (thêm 2026-11-20). Mã lưu ở máy chủ; hiển thị mã ngắn trong bảng, tên đầy đủ trong form và chi tiết.
 */
export const CATEGORY = {
    SQ: { label: 'Sĩ quan', short: 'SQ' },
    QNCN: { label: 'Quân nhân chuyên nghiệp', short: 'QNCN' },
    'HSQ-CS': { label: 'Hạ sĩ quan - chiến sĩ', short: 'HSQ-CS' },
};

/** Lựa chọn đối tượng cho ô chọn: "SQ - Sĩ quan"... */
export function categoryOptions() {
    return Object.entries(CATEGORY).map(([value, c]) => ({ value, label: `${c.short} - ${c.label}` }));
}

/** Phải khớp UserService.DEFAULT_PASSWORD ở máy chủ: mật khẩu của tài khoản mới cấp. */
export const DEFAULT_PASSWORD = '123456789';

/** Độ dài mật khẩu tối thiểu mặc định (cấu hình password.min-length ở máy chủ); máy chủ luôn kiểm lại. */
export const PASSWORD_MIN_LENGTH = 8;

/** Nhãn quyền; cấp admin suy ra từ mã đơn vị (cấp 1 không có dấu chấm). */
export function roleText(role, unitCode) {
    if (role === 'SUPER_ADMIN') return 'Super Admin';
    if (role === 'ADMIN') return unitCode && unitCode.includes('.') ? 'Admin cấp 2' : 'Admin cấp 1';
    return 'Người dùng';
}

/** Lựa chọn cho ô chọn từ một danh mục; giữ mục đang dùng dù đã khóa để dữ liệu cũ vẫn hiện đúng. */
export function catalogOptions(items, { currentId = null, withSymbol = false } = {}) {
    return items
        .filter((i) => i.active || String(i.id) === String(currentId))
        .map((i) => ({
            value: String(i.id),
            label: (withSymbol ? `${i.symbol} · ${i.name}` : i.name) + (i.active ? '' : ' (đã khóa)'),
        }));
}
