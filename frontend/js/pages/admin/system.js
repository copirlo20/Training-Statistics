import { api } from '../../core/api.js';
import { formatDateTime } from '../../core/format.js';
import { append, h, replace } from '../../ui/escape.js';
import { createForm } from '../../ui/form.js';
import { icon } from '../../ui/icons.js';
import { pageHead } from '../../ui/page-head.js';
import { rowActions } from '../../ui/row-actions.js';
import { createTable } from '../../ui/table.js';
import { toast } from '../../ui/toast.js';

/**
 * Quản trị hệ thống (M7.1, M7.3), chỉ Super Admin: tình trạng (phiên bản, dữ liệu, ổ đĩa, cảnh báo), danh sách bản sao
 * lưu (tải về, tạo ngay), cài đặt sao lưu tự động, cách khôi phục. Nhật ký thao tác ở trang riêng.
 */

const KIND = {
    auto: { label: 'Tự động', badge: 'badge' },
    manual: { label: 'Thủ công', badge: 'badge badge-success' },
    upgrade: { label: 'Trước nâng cấp', badge: 'badge badge-warning' },
};

function bytes(n) {
    if (n === null || n === undefined) return 'Không rõ';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let v = n;
    let i = 0;
    while (v >= 1024 && i < units.length - 1) {
        v /= 1024;
        i++;
    }
    return `${v.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
}

export async function render(container) {
    const warnSlot = h('div');
    const statusSlot = h('div');
    const settingsSlot = h('div');

    const table = createTable({
        caption: 'Bản sao lưu',
        emptyText: 'Chưa có bản sao lưu nào.',
        columns: [
            { key: 'name', label: 'Tên file', render: (r) => h('span', { class: 'mono break-any' }, r.name) },
            { key: 'kind', label: 'Loại', render: (r) => h('span', { class: KIND[r.kind]?.badge || 'badge' }, KIND[r.kind]?.label || r.kind) },
            { key: 'createdAt', label: 'Thời điểm', render: (r) => h('span', { class: 'nowrap' }, formatDateTime(r.createdAt)) },
            { key: 'size', label: 'Dung lượng', render: (r) => bytes(r.sizeBytes) },
            { key: 'actions', label: 'Thao tác', actions: true, render: (r) => rowActions([
                { icon: 'download', label: `Tải về ${r.name}`, href: `/api/v1/admin/backups/${encodeURIComponent(r.name)}` }]) },
        ],
        load: async () => {
            const res = await api('/admin/backups');
            drawSettings(res.settings);
            return { items: res.items, page: 0, size: Math.max(res.items.length, 1), total: res.items.length };
        },
    });

    async function drawStatus() {
        try {
            const s = await api('/admin/health');
            replace(warnSlot, s.warnings.length ? h('div', { class: 'alert alert-warning', role: 'status' },
                h('div', null, h('p', null, h('strong', null, 'Cần chú ý')), h('ul', null, s.warnings.map((w) => h('li', null, w))))) : null);
            const row = (label, value) => [h('dt', null, label), h('dd', null, value)];
            replace(statusSlot, h('dl', { class: 'dl' },
                row('Phiên bản ứng dụng', s.appVersion),
                row('Phiên bản dữ liệu', s.schemaVersion ? `V${s.schemaVersion}` : 'Không rõ'),
                row('Dữ liệu', [h('span', { class: 'mono break-any' }, s.dataDir), h('span', { class: 'cell-sub' },
                    `${bytes(s.dbSizeBytes + s.walSizeBytes)} · ổ đĩa còn trống ${bytes(s.dataFreeBytes)}`)]),
                row('Thư mục sao lưu', [h('span', { class: 'mono break-any' }, s.backupDir), h('span', { class: 'cell-sub' },
                    `ổ đĩa còn trống ${bytes(s.backupFreeBytes)}${s.backupSameDisk ? ' · cùng ổ đĩa với dữ liệu' : ''}`)]),
                row('Sao lưu tự động', `Hằng ngày lúc ${s.backupTime}, giữ ${s.backupKeep} bản gần nhất`),
                row('Bản tự động gần nhất', s.lastAutoBackupAt ? formatDateTime(s.lastAutoBackupAt) : 'Chưa có'),
                row('Máy chủ chạy từ', formatDateTime(s.startedAt)),
                row('Java', s.javaVersion)));
        } catch (e) {
            replace(statusSlot, h('div', { class: 'alert alert-error', role: 'alert' }, h('p', null, e.message)));
        }
    }

    function drawSettings(st) {
        const form = createForm({
            columns: 2,
            fields: [
                { name: 'time', label: 'Giờ sao lưu hằng ngày', type: 'time', required: true, value: st.time,
                    hint: 'Máy tắt vào giờ này thì sao lưu ngay khi bật lại trong ngày.' },
                { name: 'keep', label: 'Số bản tự động giữ lại', type: 'number', inputmode: 'numeric', min: 1, max: 30,
                    required: true, value: String(st.keep), hint: 'Bản thủ công và bản trước nâng cấp không bị xóa tự động.',
                    validate: (v) => (/^\d+$/.test(String(v).trim()) && Number(v) >= 1 && Number(v) <= 30 ? null : 'Từ 1 đến 30 bản.') },
                { name: 'dir', label: 'Thư mục sao lưu', full: true, maxLength: 260, autocomplete: 'off', value: st.dir,
                    placeholder: st.defaultDir},
            ],
            submitLabel: 'Lưu cài đặt',
            onSubmit: async (v) => {
                await api('/admin/backup-settings', { method: 'PUT', body: { time: v.time, keep: Number(v.keep), dir: v.dir } });
                toast('Đã lưu cài đặt sao lưu.', { type: 'success' });
                table.reload();
                drawStatus();
            },
        });
        replace(settingsSlot, form.element);
    }

    const createBtn = h('button', { type: 'button', class: 'btn' }, icon('database', { small: true }), h('span', null, 'Tạo bản sao lưu ngay'));
    createBtn.addEventListener('click', async () => {
        createBtn.disabled = true;
        try {
            const e = await api('/admin/backup', { method: 'POST' });
            toast(`Đã tạo bản sao lưu ${e.name} (${bytes(e.sizeBytes)}). Bấm biểu tượng tải về trên dòng để lưu ra máy.`, { type: 'success' });
            table.reload();
            drawStatus();
        } catch (err) {
            toast(err.message, { type: 'error' });
        } finally {
            createBtn.disabled = false;
        }
    });

    append(container, [
        pageHead('Hệ thống',
            'Tình trạng máy chủ, sao lưu dữ liệu. Chỉ Super Admin.',
            [h('a', { class: 'btn btn-secondary', href: '#/admin/audit' }, icon('log', { small: true }),
                h('span', null, 'Nhật ký thao tác')), createBtn]),
        warnSlot,
        h('div', { class: 'system-grid' },
            h('section', { class: 'card', 'aria-labelledby': 'sys-status' }, h('h2', { id: 'sys-status' }, 'Tình trạng'), statusSlot),
            h('section', { class: 'card', 'aria-labelledby': 'sys-settings' }, h('h2', { id: 'sys-settings' }, 'Cài đặt sao lưu'), settingsSlot)),
        h('section', { class: 'card', 'aria-labelledby': 'sys-backups' }, h('h2', { id: 'sys-backups' }, 'Bản sao lưu'), table.element),
        h('details', { class: 'card system-restore' },
            h('summary', null, 'Cách khôi phục từ bản sao lưu'),
            h('ol', null,
                h('li', null, 'Tải bản sao lưu cần dùng (hoặc lấy trong thư mục sao lưu trên máy chủ).'),
                h('li', null, 'Trên máy chủ, dừng dịch vụ "Training Statistics" (services.msc hoặc lệnh: sc stop TrainingStats).'),
                h('li', null, 'Đổi tên file dữ liệu hiện tại trainingstats.db (và trainingstats.db-wal, trainingstats.db-shm nếu có) để giữ lại, rồi chép bản sao lưu vào thư mục dữ liệu với tên trainingstats.db.'),
                h('li', null, 'Khởi động lại dịch vụ (sc start TrainingStats), đăng nhập và kiểm tra dữ liệu. Chi tiết: docs/OPERATIONS-GUIDE.md mục 9.'))),
    ]);
    drawStatus();
    table.reload(true);
}
