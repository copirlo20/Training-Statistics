import { api } from '../core/api.js';
import { h, replace } from './escape.js';
import { icon } from './icons.js';
import { confirmDialog } from './modal.js';
import { createTable } from './table.js';
import { toast } from './toast.js';

/**
 * Giao diện nhập Excel dùng chung (M5.1): tải file mẫu, chọn file, kiểm tra (dryRun), xem lỗi theo dòng và cột
 * (tải được danh sách lỗi dạng CSV), xem trước việc sẽ làm, rồi mới ghi. Máy chủ ghi tất cả hoặc không ghi gì, và
 * kiểm lại toàn bộ file khi ghi, nên giao diện chỉ cho bấm "Ghi" khi lần kiểm gần nhất không có lỗi.
 *
 * @param {{
 *   templateUrl: string,                     đường dẫn tải file mẫu (GET, cùng máy chủ)
 *   uploadPath: (dryRun: boolean) => string, đường dẫn API (sau /api/v1) để gửi file
 *   actionLabels?: Record<string, string>,   nhãn cho từng loại việc (CREATE, UPDATE, GRADE, SKIP...)
 *   beforeCheck?: () => string | null,       kiểm thêm trước khi gửi (ví dụ chưa chọn đợt); trả câu lỗi hoặc null
 *   doneText?: (report) => string,           câu báo sau khi ghi xong
 *   onWritten?: (report) => void,
 *   note?: string,                           lưu ý riêng của loại dữ liệu, hiện ở chân thẻ
 * }} options
 *
 * Bố cục gọn (đổi 2026-11-19): một thẻ với 3 bước nằm ngang từ 1024px (xếp dọc trên điện thoại), kết quả kiểm tra
 * nằm ngay dưới thẻ.
 */
const MAX_BYTES = 5 * 1024 * 1024;

const DEFAULT_LABELS = {
    CREATE: 'Tạo mới', UPDATE: 'Cập nhật', ADD: 'Thêm vào nội dung kiểm tra', GRADE: 'Nhập điểm', SKIP: 'Bỏ qua',
};

export function createImporter({ templateUrl, uploadPath, actionLabels = {}, beforeCheck, doneText, onWritten, note }) {
    const labels = { ...DEFAULT_LABELS, ...actionLabels };
    const inputId = 'import-file-' + Math.random().toString(36).slice(2, 8);
    const fileInput = h('input', {
        id: inputId, class: 'input', type: 'file', name: 'file',
        accept: '.xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'aria-describedby': inputId + '-hint ' + inputId + '-error',
    });
    const fileError = h('div', { class: 'field-error', id: inputId + '-error' });
    const checkBtn = h('button', { type: 'submit', class: 'btn' }, icon('search', { small: true }), h('span', null, 'Kiểm tra file'));
    const resultSlot = h('div', { class: 'import-result', 'aria-live': 'polite' });
    const checkStatus = h('p', { class: 'import-step-status' }, 'Chưa ghi gì cho tới khi bấm "Ghi dữ liệu".');
    let lastFile = null;

    const step = (no, title, ...content) => h('li', { class: 'import-step' },
        h('span', { class: 'import-step-no', 'aria-hidden': 'true' }, String(no)),
        h('div', { class: 'import-step-body' }, h('h2', { class: 'import-step-title' }, `Bước ${no}. ${title}`), content));
    const steps = [
        step(1, 'Tải file mẫu',
            h('p', { class: 'import-step-text' }, 'Ô chọn và ràng buộc có sẵn; sheet đầu tiên "HuongDan" giải thích từng cột.'),
            h('a', { class: 'btn btn-secondary', href: templateUrl },
                icon('log', { small: true }), h('span', null, 'Tải file mẫu (.xlsx)'))),
        step(2, 'Chọn file đã điền',
            h('label', { class: 'visually-hidden', for: inputId }, 'File Excel (.xlsx)'),
            fileInput,
            h('div', { class: 'field-hint', id: inputId + '-hint' }, 'File .xlsx, tối đa 5 MB và 5.000 dòng.'),
            fileError),
        step(3, 'Kiểm tra rồi ghi', checkStatus, checkBtn),
    ];
    const setStep = (i, state) => steps[i].classList.toggle('is-done', state);

    fileInput.addEventListener('change', () => {
        fileError.textContent = '';
        fileInput.setAttribute('aria-invalid', 'false');
        setStep(1, !!fileInput.files?.[0]);
        setStep(2, false);
        checkStatus.textContent = 'Chưa ghi gì cho tới khi bấm "Ghi dữ liệu".';
        replace(resultSlot);
    });

    const form = h('form', { class: 'import-form', novalidate: true },
        h('ol', { class: 'import-steps' }, steps));

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        const problem = pickProblem();
        if (problem) {
            fileError.textContent = problem;
            fileInput.setAttribute('aria-invalid', 'true');
            fileInput.focus();
            return;
        }
        lastFile = fileInput.files[0];
        await send(true, checkBtn);
    });

    function pickProblem() {
        const extra = beforeCheck?.();
        if (extra) return extra;
        const file = fileInput.files?.[0];
        if (!file) return 'Chọn file Excel để nhập.';
        if (!/\.xlsx$/i.test(file.name)) return 'Chỉ nhận file .xlsx. Hãy lưu file theo định dạng Excel Workbook.';
        if (file.size > MAX_BYTES) return 'File quá lớn. Tối đa 5 MB, hãy chia thành nhiều file.';
        return null;
    }

    async function send(dryRun, button) {
        const form = new FormData();
        form.append('file', lastFile);
        const old = button.disabled;
        button.disabled = true;
        if (dryRun) replace(resultSlot, h('p', { class: 'loading' }, 'Đang kiểm tra file...'));
        try {
            const report = await api(uploadPath(dryRun), { method: 'POST', form });
            if (!dryRun && report.written) {
                showDone(report);
            } else {
                showReport(report);
            }
            setStep(2, report.errorCount === 0);
            checkStatus.textContent = report.written ? 'Đã ghi xong.'
                : report.errorCount > 0 ? `Có ${report.errorCount} lỗi, xem bên dưới.`
                    : 'Không có lỗi. Bấm "Ghi dữ liệu" bên dưới.';
        } catch (err) {
            replace(resultSlot, h('div', { class: 'alert alert-error', role: 'alert' }, h('p', null, err.message)));
        } finally {
            button.disabled = old;
        }
    }

    function countsText(counts) {
        return Object.entries(counts || {}).map(([k, n]) => `${labels[k] || k}: ${n}`).join(' · ');
    }

    function showReport(report) {
        const hasErrors = report.errorCount > 0;
        const showSheet = report.errors.some((e) => e.sheet) || report.rows.some((r) => r.sheet);
        const parts = [];
        if (hasErrors) {
            parts.push(h('div', { class: 'alert alert-error', role: 'alert' },
                h('p', null, `Có ${report.errorCount} lỗi trong ${report.totalRows} dòng. Chưa ghi gì. `
                    + 'Sửa file theo danh sách dưới đây rồi kiểm tra lại.')));
            parts.push(h('div', { class: 'btn-row import-tools' },
                h('button', { type: 'button', class: 'btn btn-secondary', onclick: () => downloadErrors(report) },
                    icon('log', { small: true }), h('span', null, 'Tải danh sách lỗi (CSV)'))));
            parts.push(clientTable('Lỗi theo dòng', report.errors, [
                showSheet ? { key: 'sheet', label: 'Sheet', render: (e) => e.sheet || '' } : null,
                { key: 'row', label: 'Dòng', render: (e) => String(e.row) },
                { key: 'column', label: 'Cột', render: (e) => e.column || '' },
                { key: 'message', label: 'Lỗi', render: (e) => h('span', { class: 'text-danger' }, e.message) },
            ].filter(Boolean), 'Không có lỗi.'));
        } else {
            const work = report.rows.filter((r) => r.action !== 'SKIP').length;
            parts.push(h('div', { class: 'alert alert-success', role: 'status' },
                h('p', null, `Đọc được ${report.totalRows} dòng, không có lỗi. ${countsText(report.counts)}. `
                    + 'Đối chiếu bảng dưới đây rồi bấm "Ghi dữ liệu".')));
            const writeBtn = h('button', { type: 'button', class: 'btn', disabled: work === 0 },
                h('span', null, 'Ghi dữ liệu'));
            writeBtn.addEventListener('click', async () => {
                const ok = await confirmDialog(`Ghi ${work} dòng vào hệ thống? ${countsText(report.counts)}.`,
                    { title: 'Ghi dữ liệu', okLabel: 'Ghi dữ liệu' });
                if (ok) await send(false, writeBtn);
            });
            parts.push(h('div', { class: 'btn-row import-tools' }, writeBtn,
                work === 0 ? h('span', { class: 'text-muted' }, 'Không có dòng nào cần ghi.') : null));
        }
        if (report.rows.length > 0) {
            parts.push(h('h3', { class: 'pick-heading' }, hasErrors ? 'Các dòng hợp lệ' : 'Việc sẽ làm'));
            parts.push(clientTable('Xem trước', report.rows, [
                showSheet ? { key: 'sheet', label: 'Sheet', render: (r) => r.sheet || '' } : null,
                { key: 'row', label: 'Dòng', render: (r) => String(r.row) },
                { key: 'action', label: 'Việc', render: (r) =>
                    h('span', { class: r.action === 'SKIP' ? 'badge' : 'badge badge-success' }, labels[r.action] || r.action) },
                { key: 'label', label: 'Nội dung', render: (r) => r.label },
                { key: 'detail', label: 'Đối chiếu', render: (r) => r.detail || '' },
            ].filter(Boolean), 'Không có dòng nào.'));
        }
        replace(resultSlot, parts);
    }

    function showDone(report) {
        replace(resultSlot, h('div', { class: 'alert alert-success', role: 'status' },
            h('p', null, doneText ? doneText(report) : `Đã ghi xong. ${countsText(report.counts)}.`)));
        toast('Đã nhập dữ liệu từ Excel.', { type: 'success' });
        fileInput.value = '';
        lastFile = null;
        onWritten?.(report);
    }

    function downloadErrors(report) {
        const quote = (v) => `"${String(v ?? '').replace(/"/g, '""')}"`;
        const lines = [['Sheet', 'Dòng', 'Cột', 'Lỗi'].map(quote).join(',')];
        for (const e of report.errors) lines.push([e.sheet, e.row, e.column, e.message].map(quote).join(','));
        // BOM để Excel mở đúng tiếng Việt.
        const blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
        const url = URL.createObjectURL(blob);
        const a = h('a', { href: url, download: 'loi-nhap-excel.csv', hidden: true });
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    }

    const element = h('div', { class: 'importer' },
        h('section', { class: 'card import-card' },
            form,
            note ? h('p', { class: 'import-note' }, icon('alert', { small: true }), h('span', null, note)) : null),
        resultSlot);
    return { element };
}

/** Bảng phân trang phía trình duyệt cho danh sách đã có sẵn (lỗi, xem trước). */
function clientTable(caption, items, columns, emptyText) {
    const table = createTable({
        caption,
        columns,
        emptyText,
        pageSize: 20,
        load: async ({ page, size }) => ({ items: items.slice(page * size, page * size + size), page, size, total: items.length }),
    });
    table.reload();
    return table.element;
}
