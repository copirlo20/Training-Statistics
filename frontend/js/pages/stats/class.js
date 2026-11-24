import { api } from '../../core/api.js';
import { formatDate } from '../../core/format.js';
import { OPEN_STATUS } from '../../core/refdata.js';
import { backLink } from '../../ui/back-link.js';
import {
    columnChart, dataTable, donutChart, GRADE_SERIES, MISSING_SERIES, percentText,
} from '../../ui/charts.js';
import { append, h, replace } from '../../ui/escape.js';
import { pageHead } from '../../ui/page-head.js';
import { chartCard, gradeColumns, kpiRow, passed, segmented } from './common.js';

/**
 * Thống kê một lớp (M6.2): Super Admin và giáo viên của lớp xem cả lớp; Admin chỉ thấy người học thuộc nhánh mình
 * (máy chủ lọc). Điểm cá nhân và điểm tập thể đơn vị xem riêng.
 */
export async function render(container, params) {
    let kind = 'PERSON';
    const first = await api(`/stats/class/${params.id}`);
    const info = first.classInfo;
    const body = h('div', { class: 'stats-body', 'aria-live': 'polite' });

    const show = (data) => {
        const c = data.counts;
        const histogram = data.histogram.map((b) => ({
            label: b.fromX10 === 90 ? '9–10' : `${b.fromX10 / 10}–${b.toX10 / 10}`,
            value: b.count, text: String(b.count),
            tip: `Điểm từ ${b.fromX10 / 10} đến ${b.fromX10 === 90 ? 'hết 10' : `dưới ${b.toX10 / 10}`}: ${b.count}`,
        }));
        const maxCount = Math.max(1, ...histogram.map((b) => b.value));
        const step = Math.max(1, Math.ceil(maxCount / 4));
        const ticks = [0, step, step * 2, step * 3, step * 4];
        replace(body,
            kpiRow(c, kind),
            h('div', { class: 'chart-grid' },
                chartCard('Phân bố xếp loại', c.tested ? `${c.tested}/${c.total} đã có điểm` : 'Chưa có điểm',
                    donutChart({ series: [...GRADE_SERIES, ...MISSING_SERIES], counts: c, label: 'Phân bố xếp loại của nội dung kiểm tra',
                        center: c.tested ? percentText(passed(c), c.tested) : '—', centerSub: 'đạt trở lên' })),
                chartCard('Phân bố điểm', 'Số người theo từng khoảng 1 điểm',
                    c.tested ? columnChart({ items: histogram, max: ticks[4], ticks, label: 'Phân bố điểm của nội dung kiểm tra' })
                        : h('p', { class: 'chart-empty' }, 'Chưa có điểm.'))),
            chartCard('Theo đơn vị lúc tham gia', 'Tỷ lệ xếp loại tính trên số đã có điểm',
                dataTable({ caption: 'Thống kê nội dung kiểm tra theo đơn vị', rows: data.byUnit,
                    columns: gradeColumns((r) => h('span', null, r.name, h('span', { class: 'cell-sub' }, r.code))),
                    foot: data.byUnit.length > 1 ? { name: 'Tổng cộng', code: '', counts: c } : null })));
    };

    const kindSwitch = segmented('Loại điểm', [['PERSON', 'Cá nhân'], ['UNIT', 'Tập thể đơn vị']], kind, async (v) => {
        kind = v;
        replace(body, h('p', { class: 'loading' }, 'Đang tải số liệu...'));
        try {
            show(await api(`/stats/class/${params.id}`, { query: { kind } }));
        } catch (e) {
            replace(body, h('div', { class: 'alert alert-error', role: 'alert' }, h('p', null, e.message)));
        }
    });

    const st = OPEN_STATUS[info.status] || { label: info.status, badge: 'badge' };
    append(container, [
        backLink(`#/classes/${info.id}`, info.name),
        pageHead(`Thống kê: ${info.name}`,
            [h('span', { class: st.badge }, st.label), ' ' + [info.periodName, formatDate(info.examDate)].filter(Boolean).join(' · ')]),
        h('div', { class: 'stats-switches' }, kindSwitch),
        body]);
    show(first);
}
