/** Định dạng hiển thị theo giờ Việt Nam. Máy chủ luôn gửi thời gian dạng ISO-8601 UTC. */

const ZONE = 'Asia/Ho_Chi_Minh';

const dateTimeFmt = new Intl.DateTimeFormat('vi-VN', {
    timeZone: ZONE, day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false,
});
const dateFmt = new Intl.DateTimeFormat('vi-VN', { timeZone: ZONE, day: '2-digit', month: '2-digit', year: 'numeric' });

export function formatDateTime(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? iso : dateTimeFmt.format(d);
}

/** Ngày dạng yyyy-MM-dd (không có giờ) thành dd/MM/yyyy, không đổi múi giờ. */
export function formatDate(value) {
    if (!value) return '';
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
    if (m) return `${m[3]}/${m[2]}/${m[1]}`;
    const d = new Date(value);
    return Number.isNaN(d.getTime()) ? value : dateFmt.format(d);
}

/** Chuỗi để so khớp khi tìm ở trình duyệt: bỏ dấu tiếng Việt, chữ thường (giống cột search_text ở máy chủ). */
export function searchKey(s) {
    return String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd').replace(/Đ/g, 'D').toLowerCase().trim();
}
