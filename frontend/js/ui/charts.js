import { h } from './escape.js';

/**
 * Biểu đồ tự viết, không thư viện ngoài (M6.2):
 * - donutChart: biểu đồ vòng (SVG), chú giải bên cạnh có số và tỷ lệ.
 * - stackedBars: thanh ngang chồng 100% theo từng dòng (HTML/CSS, nhãn dài xuống dòng được trên điện thoại).
 * - barList: thanh ngang một chuỗi số liệu (điểm trung bình, tỷ lệ đạt...).
 * - columnChart: biểu đồ cột (SVG), vẽ lại theo bề rộng khung nên chữ không bị co nhỏ; nhãn dài được rút gọn.
 *
 * Màu lấy từ lớp CSS `tone-*` (biến trong tokens.css), không đặt màu trong JS. Màu chỉ là kênh phụ: luôn có chú giải
 * bằng chữ, và trang dùng biểu đồ luôn kèm bảng số liệu. Rê chuột hoặc chạm vào phần tử để xem chú thích; đó chỉ là
 * thông tin thêm, không có gì chỉ xem được khi rê.
 */

const SVG_NS = 'http://www.w3.org/2000/svg';

/** Chuỗi xếp loại theo thứ tự cố định (màu theo xếp loại, không theo thứ hạng). */
export const GRADE_SERIES = [
    { key: 'fail', label: 'Không đạt', tone: 'fail' },
    { key: 'pass', label: 'Đạt', tone: 'pass' },
    { key: 'good', label: 'Khá', tone: 'good' },
    { key: 'excellent', label: 'Giỏi', tone: 'excellent' },
];

/** Hai trạng thái chưa có điểm, màu trung tính. */
export const MISSING_SERIES = [
    { key: 'absent', label: 'Không kiểm tra', tone: 'absent' },
    { key: 'notTested', label: 'Chưa kiểm tra', tone: 'pending' },
];

export function percent(n, total) {
    return total > 0 ? Math.round((n * 1000) / total) / 10 : 0;
}

export function percentText(n, total) {
    return `${percent(n, total)}%`;
}

/** Điểm trung bình ×10 thành chữ, hai chữ số thập phân (chỉ để xem). */
export function avgText(avgX10) {
    return avgX10 === null || avgX10 === undefined ? '—' : (avgX10 / 10).toFixed(2);
}

function s(tag, attrs = {}, ...children) {
    const el = document.createElementNS(SVG_NS, tag);
    for (const [k, v] of Object.entries(attrs)) {
        if (v !== null && v !== undefined) el.setAttribute(k, String(v));
    }
    for (const c of children) {
        if (c === null || c === undefined) continue;
        el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
    }
    return el;
}

// ------------------------------------------------------------------ chú thích khi rê hoặc chạm

/** Gắn chú thích nổi cho các phần tử có data-tip trong khung `frame` (khung phải là position: relative). */
function attachTips(frame) {
    const tip = h('div', { class: 'chart-tip', role: 'presentation', hidden: true });
    frame.appendChild(tip);
    const show = (target, x, y) => {
        tip.textContent = target.dataset.tip;
        tip.hidden = false;
        const box = frame.getBoundingClientRect();
        const w = tip.offsetWidth;
        const left = Math.min(Math.max(x - box.left - w / 2, 0), Math.max(box.width - w, 0));
        tip.style.left = `${left}px`;
        tip.style.top = `${Math.max(y - box.top - tip.offsetHeight - 10, 0)}px`;
    };
    frame.addEventListener('pointermove', (e) => {
        const target = e.target.closest?.('[data-tip]');
        if (target && frame.contains(target)) show(target, e.clientX, e.clientY);
        else tip.hidden = true;
    });
    frame.addEventListener('pointerleave', () => { tip.hidden = true; });
    frame.addEventListener('pointerdown', (e) => {
        const target = e.target.closest?.('[data-tip]');
        if (target) show(target, e.clientX, e.clientY);
    });
}

function legend(series, valueOf, total) {
    return h('ul', { class: 'chart-legend' }, series.map((sr) => {
        const n = valueOf ? valueOf(sr) : null;
        return h('li', null,
            h('span', { class: ['chart-swatch', `tone-${sr.tone}`], 'aria-hidden': 'true' }),
            h('span', { class: 'chart-legend-label' }, sr.label),
            n === null ? null : h('span', { class: 'chart-legend-value' },
                h('strong', null, String(n)), total ? ` · ${percentText(n, total)}` : null));
    }));
}

// ------------------------------------------------------------------ vòng

/**
 * @param {{series: {key,label,tone}[], counts: object, center?: string, centerSub?: string, label: string}} o
 *   counts[key] là số lượng của từng phần.
 */
export function donutChart({ series, counts, center, centerSub, label }) {
    const total = series.reduce((sum, sr) => sum + (counts[sr.key] || 0), 0);
    const R = 40;
    const GAP = series.filter((sr) => counts[sr.key] > 0).length > 1 ? 0.8 : 0;
    const svg = s('svg', { viewBox: '0 0 100 100', class: 'donut', role: 'img', 'aria-label': label });
    svg.appendChild(s('circle', { cx: 50, cy: 50, r: R, class: 'donut-track', 'stroke-width': 14, fill: 'none' }));
    let offset = 0;
    for (const sr of series) {
        const n = counts[sr.key] || 0;
        if (n === 0 || total === 0) continue;
        const len = (n / total) * 100;
        const arc = s('circle', {
            cx: 50, cy: 50, r: R, fill: 'none', 'stroke-width': 14, pathLength: 100,
            class: `donut-seg tone-${sr.tone}`,
            'stroke-dasharray': `${Math.max(len - GAP, 0.01)} ${100 - Math.max(len - GAP, 0.01)}`,
            'stroke-dashoffset': String(25 - offset),
            'data-tip': `${sr.label}: ${n} (${percentText(n, total)})`,
        });
        svg.appendChild(arc);
        offset += len;
    }
    svg.appendChild(s('text', { x: 50, y: centerSub ? 49 : 54, class: 'donut-value', 'text-anchor': 'middle' },
        center ?? String(total)));
    if (centerSub) svg.appendChild(s('text', { x: 50, y: 62, class: 'donut-sub', 'text-anchor': 'middle' }, centerSub));
    const frame = h('div', { class: 'chart-frame donut-frame' }, svg);
    attachTips(frame);
    return h('div', { class: 'chart-donut' }, frame, legend(series, (sr) => counts[sr.key] || 0, total));
}

// ------------------------------------------------------------------ thanh ngang chồng

/**
 * Mỗi dòng là một thanh 100% chia theo chuỗi; bên phải là tổng. Dòng có href thì nhãn là liên kết.
 * @param {{rows: {label: string, sub?: string, href?: string, counts: object}[], series: object[], label: string,
 *          emptyText?: string}} o
 */
export function stackedBars({ rows, series, label, emptyText = 'Chưa có số liệu.' }) {
    if (rows.length === 0) return h('p', { class: 'chart-empty' }, emptyText);
    const list = h('ul', { class: 'hbar-list', 'aria-label': label }, rows.map((r) => {
        const total = series.reduce((sum, sr) => sum + (r.counts[sr.key] || 0), 0);
        const track = h('span', { class: 'hbar-track' });
        for (const sr of series) {
            const n = r.counts[sr.key] || 0;
            if (n === 0) continue;
            const seg = h('span', { class: ['hbar-seg', `tone-${sr.tone}`],
                'data-tip': `${r.label} · ${sr.label}: ${n} (${percentText(n, total)})` });
            seg.style.flexGrow = String(n);
            track.appendChild(seg);
        }
        const name = r.href ? h('a', { href: r.href }, r.label) : r.label;
        return h('li', { class: 'hbar-row' },
            h('span', { class: 'hbar-label' }, name, r.sub ? h('span', { class: 'cell-sub' }, r.sub) : null),
            track,
            h('span', { class: 'hbar-value' }, String(total)));
    }));
    const frame = h('div', { class: 'chart-frame' }, legend(series), list);
    attachTips(frame);
    return frame;
}

// ------------------------------------------------------------------ thanh ngang một chuỗi

/**
 * @param {{items: {label: string, sub?: string, value: number|null, text: string, tip?: string}[], max: number,
 *          label: string, tone?: string, emptyText?: string}} o
 */
export function barList({ items, max, label, tone = 'primary', emptyText = 'Chưa có số liệu.' }) {
    if (items.length === 0) return h('p', { class: 'chart-empty' }, emptyText);
    const list = h('ul', { class: 'hbar-list', 'aria-label': label }, items.map((it) => {
        const track = h('span', { class: 'hbar-track hbar-track-single' });
        if (it.value !== null && it.value > 0) {
            const bar = h('span', { class: ['hbar-seg', `tone-${tone}`], 'data-tip': it.tip || `${it.label}: ${it.text}` });
            bar.style.width = `${Math.min(100, (it.value / max) * 100)}%`;
            track.appendChild(bar);
        }
        return h('li', { class: 'hbar-row' },
            h('span', { class: 'hbar-label' }, it.label, it.sub ? h('span', { class: 'cell-sub' }, it.sub) : null),
            track,
            h('span', { class: 'hbar-value' }, it.text));
    }));
    const frame = h('div', { class: 'chart-frame' }, list);
    attachTips(frame);
    return frame;
}

// ------------------------------------------------------------------ cột

const FONT_PX = 12;
const CHAR_PX = 6.6;
const COL_MAX = 28;

function shorten(text, px) {
    const max = Math.max(Math.floor(px / CHAR_PX), 2);
    return text.length <= max ? text : text.slice(0, max - 1) + '…';
}

/** Nhãn dưới cột: tối đa 2 dòng theo bề rộng dải, phần thừa rút gọn bằng "…". */
function wrapLabel(text, px) {
    const max = Math.max(Math.floor(px / CHAR_PX), 2);
    if (text.length <= max) return [text];
    const words = text.split(/\s+/);
    let first = '';
    while (words.length && (first ? first.length + 1 : 0) + words[0].length <= max) {
        first = first ? `${first} ${words.shift()}` : words.shift();
    }
    if (!first) return [shorten(text, px)];
    return [first, shorten(words.join(' '), px)];
}

/**
 * Cột đứng một chuỗi số liệu, trục tung từ 0 tới `max` với các vạch `ticks`.
 * @param {{items: {label: string, value: number|null, text: string, tip?: string}[], max: number, ticks: number[],
 *          tickText?: (v: number) => string, label: string, tone?: string, height?: number, emptyText?: string}} o
 */
export function columnChart({ items, max, ticks, tickText = String, label, tone = 'primary', height = 220,
    emptyText = 'Chưa có số liệu.' }) {
    if (items.length === 0) return h('p', { class: 'chart-empty' }, emptyText);
    const frame = h('div', { class: 'chart-frame chart-columns' });
    const desc = items.map((it) => `${it.label}: ${it.text}`).join('; ');

    const draw = (width) => {
        const left = 32;
        const top = 18;
        const bottom = 42;
        const plotW = Math.max(width - left - 4, 40);
        const plotH = height - top - bottom;
        const band = plotW / items.length;
        const colW = Math.min(COL_MAX, band * 0.6);
        const y = (v) => top + plotH - (Math.max(0, Math.min(v, max)) / max) * plotH;
        const svg = s('svg', { viewBox: `0 0 ${width} ${height}`, width, height, role: 'img',
            'aria-label': `${label}. ${desc}` });
        for (const t of ticks) {
            svg.appendChild(s('line', { x1: left, x2: left + plotW, y1: y(t), y2: y(t),
                class: t === 0 ? 'chart-axis' : 'chart-grid-line' }));
            svg.appendChild(s('text', { x: left - 6, y: y(t) + 4, 'text-anchor': 'end', class: 'chart-tick' }, tickText(t)));
        }
        const showValues = band >= 26;
        items.forEach((it, i) => {
            const cx = left + band * i + band / 2;
            if (it.value !== null && it.value > 0) {
                const yTop = y(it.value);
                const hgt = top + plotH - yTop;
                const r = Math.min(4, colW / 2, hgt);
                // Bo tròn đầu cột, chân cột vuông.
                const x0 = cx - colW / 2;
                const x1 = cx + colW / 2;
                const base = top + plotH;
                const d = `M${x0},${base} L${x0},${yTop + r} Q${x0},${yTop} ${x0 + r},${yTop} L${x1 - r},${yTop} `
                    + `Q${x1},${yTop} ${x1},${yTop + r} L${x1},${base} Z`;
                svg.appendChild(s('path', { d, class: `chart-col tone-${tone}` }));
                if (showValues) {
                    svg.appendChild(s('text', { x: cx, y: yTop - 5, 'text-anchor': 'middle', class: 'chart-value' }, it.text));
                }
            }
            // Vùng nhận rê/chạm phủ cả dải, lớn hơn cột.
            svg.appendChild(s('rect', { x: left + band * i, y: top, width: band, height: plotH, class: 'chart-hit',
                'data-tip': it.tip || `${it.label}: ${it.text}` }));
            const lines = wrapLabel(it.label, band - 4);
            svg.appendChild(s('text', { x: cx, y: height - bottom + FONT_PX + 6, 'text-anchor': 'middle', class: 'chart-tick' },
                ...lines.map((line, k) => s('tspan', { x: cx, dy: k === 0 ? 0 : FONT_PX + 2 }, line))));
        });
        const old = frame.querySelector('svg');
        if (old) old.replaceWith(svg); else frame.prepend(svg);
    };

    let lastWidth = 0;
    const observer = new ResizeObserver((entries) => {
        const w = Math.floor(entries[0].contentRect.width);
        if (w > 0 && Math.abs(w - lastWidth) >= 2) {
            lastWidth = w;
            draw(w);
        }
    });
    observer.observe(frame);
    attachTips(frame);
    return frame;
}

/** Bảng số liệu đơn giản đi kèm biểu đồ (không phân trang). columns: {label, render(row), num?: boolean}. */
export function dataTable({ caption, columns, rows, foot }) {
    const cell = (tag, c, content) => h(tag, { class: c.num ? 'num' : null, 'data-label': tag === 'td' ? c.label : null },
        h('div', { class: 'cell-value' }, content));
    return h('div', { class: 'data-table' }, h('div', { class: 'table-scroll' },
        h('table', { class: 'data data-stats' },
            h('caption', { class: 'visually-hidden' }, caption),
            h('thead', null, h('tr', null, columns.map((c) => h('th', { scope: 'col', class: c.num ? 'num' : null }, c.label)))),
            h('tbody', null, rows.map((r) => h('tr', null, columns.map((c) => cell('td', c, c.render(r)))))),
            foot ? h('tfoot', null, h('tr', null, columns.map((c) => cell('td', c, c.render(foot))))) : null)));
}
