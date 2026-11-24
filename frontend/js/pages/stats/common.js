import { h, replace } from '../../ui/escape.js';
import { icon } from '../../ui/icons.js';
import { avgText, percentText } from '../../ui/charts.js';

/** Phần dùng chung của các trang thống kê: thẻ số liệu, cột bảng xếp loại, nút chọn loại điểm. */

export const KIND_LABEL = {
    PERSON: 'Điểm cá nhân',
    UNIT: 'Điểm tập thể đơn vị',
};

/** Bỏ tham số rỗng trước khi gửi. */
export function cleanQuery(obj) {
    return Object.fromEntries(Object.entries(obj).filter(([, v]) => v !== '' && v !== null && v !== undefined));
}

export function passed(c) {
    return c.pass + c.good + c.excellent;
}

/** Thẻ số liệu; có href thì cả thẻ là liên kết. Giá trị null (không tải được) hiện "—". */
export function statCard(label, value, note, iconName, tone, href) {
    return h(href ? 'a' : 'div', { class: ['stat-card', href ? null : 'stat-card-static'], href },
        h('span', { class: 'stat-body' },
            h('span', { class: 'stat-label' }, label),
            h('span', { class: 'stat-value' }, value === null || value === undefined ? '—' : String(value)),
            note ? h('span', { class: 'stat-note' }, note) : null),
        h('span', { class: ['stat-icon', tone], 'aria-hidden': 'true' }, icon(iconName)));
}

/** Hàng thẻ số liệu tổng của một tập người học. */
export function kpiRow(c, kind) {
    const who = kind === 'UNIT' ? 'lượt đơn vị' : 'lượt người học';
    return h('div', { class: 'stat-grid stat-grid-kpi' },
        statCard('Tổng số', String(c.total), `${who}, không tính người đã gỡ`, 'users'),
        statCard('Đã có điểm', String(c.tested), c.total ? `${percentText(c.tested, c.total)} tổng số` : 'Chưa có ai', 'star',
            'is-success'),
        statCard('Tỷ lệ đạt trở lên', c.tested ? percentText(passed(c), c.tested) : '—',
            c.tested ? `${passed(c)}/${c.tested} đã có điểm` : 'Chưa có điểm', 'chart'),
        statCard('Điểm trung bình', avgText(c.avgX10),
            c.tested ? `Cao nhất ${(c.maxX10 / 10).toFixed(1)} · thấp nhất ${(c.minX10 / 10).toFixed(1)}` : 'Chưa có điểm',
            'list'),
        statCard('Chưa có điểm', String(c.absent + c.notTested),
            `${c.absent} không kiểm tra · ${c.notTested} chưa kiểm tra`, 'alert', 'is-warning'));
}

function countPct(n, of) {
    return of ? [h('strong', null, String(n)), h('span', { class: 'cell-sub' }, percentText(n, of))] : String(n);
}

/** Cột bảng xếp loại theo đơn vị; tỷ lệ xếp loại tính trên số đã có điểm. */
export function gradeColumns(nameOf) {
    return [
        { label: 'Đơn vị', render: nameOf },
        { label: 'Tổng số', num: true, render: (r) => String(r.counts.total) },
        { label: 'Đã có điểm', num: true, render: (r) => String(r.counts.tested) },
        { label: 'Giỏi', num: true, render: (r) => countPct(r.counts.excellent, r.counts.tested) },
        { label: 'Khá', num: true, render: (r) => countPct(r.counts.good, r.counts.tested) },
        { label: 'Đạt', num: true, render: (r) => countPct(r.counts.pass, r.counts.tested) },
        { label: 'Không đạt', num: true, render: (r) => countPct(r.counts.fail, r.counts.tested) },
        { label: 'Không KT', num: true, render: (r) => String(r.counts.absent) },
        { label: 'Chưa KT', num: true, render: (r) => String(r.counts.notTested) },
        { label: 'Điểm TB', num: true, render: (r) => avgText(r.counts.avgX10) },
    ];
}

/** Nút chọn kiểu "segmented". options: [value, label][]. Trả phần tử; gọi onChange(value) khi đổi. */
export function segmented(label, options, value, onChange) {
    const el = h('div', { class: 'segmented', role: 'group', 'aria-label': label });
    let current = value;
    const draw = () => replace(el, options.map(([v, text]) => h('button', {
        type: 'button', class: 'btn btn-secondary', 'aria-pressed': String(current === v),
        onclick: () => {
            if (current === v) return;
            current = v;
            draw();
            onChange(v);
        },
    }, text)));
    draw();
    return el;
}

export function chartCard(title, sub, ...body) {
    return h('section', { class: 'card chart-card' },
        h('div', { class: 'card-head' },
            h('h2', null, title),
            sub ? h('p', { class: 'card-sub' }, sub) : null),
        body);
}
