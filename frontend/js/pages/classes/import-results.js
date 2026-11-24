import { api } from '../../core/api.js';
import { OPEN_STATUS } from '../../core/refdata.js';
import { backLink } from '../../ui/back-link.js';
import { h, replace } from '../../ui/escape.js';
import { createImporter } from '../../ui/importer.js';
import { pageHead } from '../../ui/page-head.js';

/** Nhập điểm một lớp bằng Excel (Super Admin, giáo viên của lớp). File mẫu là danh sách đầy đủ của lớp. */
export async function render(container, params) {
    const cls = await api(`/classes/${params.id}`);
    if (!cls.access.manage) {
        replace(container, h('div', { class: 'state-page' },
            h('h1', null, 'Không nhập điểm được nội dung kiểm tra này'),
            h('p', { class: 'text-muted' }, 'Chỉ Super Admin và giám khảo của nội dung kiểm tra được nhập điểm.'),
            h('a', { class: 'btn btn-secondary', href: `#/classes/${cls.id}` }, 'Về nội dung kiểm tra')));
        return;
    }
    const st = OPEN_STATUS[cls.status] || { label: cls.status, badge: 'badge' };
    const importer = createImporter({
        templateUrl: `/api/v1/classes/${cls.id}/results/import-template`,
        uploadPath: (dryRun) => `/classes/${cls.id}/results/import?dryRun=${dryRun}`,
        doneText: (r) => `Đã ghi điểm cho ${r.counts.GRADE || 0} người.`,
        note: `File mẫu là danh sách đầy đủ của "${cls.name}": chỉ điền điểm cho người chưa có điểm. `
            + 'Người đã có điểm hiện sẵn điểm và bị khóa; đổi điểm của họ thì báo lỗi, không ghi đè.',
    });
    replace(container,
        backLink(`#/classes/${cls.id}`, cls.name),
        pageHead('Nhập điểm bằng Excel',
            [h('span', { class: st.badge }, st.label), ` ${cls.name} · ${cls.period.name}`],
            h('a', { class: 'btn btn-secondary', href: `#/classes/${cls.id}/grade` }, 'Chấm trên màn hình')),
        importer.element);
}
