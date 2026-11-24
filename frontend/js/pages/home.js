import { api } from '../core/api.js';
import { currentUser, hasRole, roleLabel } from '../core/auth.js';
import { formatDate, formatDateTime } from '../core/format.js';
import { OPEN_STATUS } from '../core/refdata.js';
import { notificationTarget } from '../ui/bell.js';
import {
    avgText, barList, columnChart, donutChart, GRADE_SERIES, MISSING_SERIES, percent, percentText, stackedBars,
} from '../ui/charts.js';
import { h, replace } from '../ui/escape.js';
import { icon } from '../ui/icons.js';
import { pageHead } from '../ui/page-head.js';
import { scoreWithGrade, stateBadge } from './classes/results.js';
import { chartCard, passed, statCard } from './stats/common.js';

/**
 * Trang chủ dạng dashboard (tham khảo AdminKit): lời chào, hàng thẻ số liệu theo vai trò, biểu đồ kết quả kiểm tra
 * (Admin, Super Admin, M6.2; điểm cá nhân và điểm tập thể đơn vị là hai thẻ riêng), lớp gần đây, thông báo mới.
 * Thông tin tài khoản xem ở trang Hồ sơ, không lặp lại ở đây. Mọi số liệu lấy từ API có sẵn và đã
 * lọc theo quyền ở máy chủ; ô nào lỗi thì hiện "—".
 */
const RECENT = 5;
const DASH_UNITS = 10;
const DASH_CONTENTS = 6;

export async function render(container) {
    const me = currentUser();
    const manager = hasRole(['ADMIN', 'SUPER_ADMIN']);
    const teaching = me.teachingClasses || [];
    const isPerson = !!me.unit;

    const sa = me.role === 'SUPER_ADMIN';
    const [users, classes, pending, unread, myClasses, notices, overview, unitOverview, ungraded] = await Promise.allSettled([
        manager ? api('/users', { query: { size: 1 } }) : null,
        api('/classes', { query: { size: RECENT } }),
        manager || teaching.length > 0 ? api('/requests', { query: { status: 'PENDING', size: 1 } }) : null,
        api('/notifications/unread-count'),
        isPerson ? api('/me/classes') : null,
        api('/notifications', { query: { size: RECENT } }),
        manager ? api('/stats/overview', { query: { group: sa ? 'top' : 'unit' } }) : null,
        manager ? api('/stats/overview', { query: { kind: 'UNIT' } }) : null,
        manager || teaching.length > 0 ? api('/classes', { query: { ungraded: true, size: 1 } }) : null,
    ]);
    const value = (r, pick) => (r.status === 'fulfilled' && r.value ? pick(r.value) : null);
    const mine = value(myClasses, (v) => v) || [];

    const stats = [];
    if (manager) {
        stats.push(statCard('Người dùng', value(users, (v) => v.total),
            me.role === 'SUPER_ADMIN' ? 'Toàn Lữ đoàn' : 'Trong phạm vi quản lý', 'users', null, '#/users'));
        stats.push(statCard('Nội dung kiểm tra', value(classes, (v) => v.total), 'Nội dung kiểm tra bạn được xem', 'class', null, '#/classes'));
        stats.push(statCard('Đề nghị chờ duyệt', value(pending, (v) => v.total), 'Cần xử lý', 'inbox', 'is-warning', '#/requests'));
    } else {
        stats.push(statCard('Nội dung kiểm tra đã tham gia', mine.length, 'Không tính nội dung kiểm tra đã bị gỡ', 'class', null, '#/me'));
        stats.push(statCard('Đã có điểm', mine.filter((c) => c.result).length, 'Số nội dung kiểm tra đã có điểm', 'star', 'is-success', '#/me'));
        stats.push(teaching.length > 0
            ? statCard('Đang làm giám khảo', teaching.length, `${value(pending, (v) => v.total) ?? 0} đề nghị chờ duyệt`, 'users', null, '#/classes')
            : statCard('Chưa kiểm tra', mine.filter((c) => !c.result).length, 'Chưa có điểm hoặc vắng', 'alert', 'is-warning', '#/me'));
    }
    stats.push(statCard('Thông báo chưa đọc', value(unread, (v) => v.count), 'Bấm để xem', 'bell', 'is-danger', '#/notifications'));

    const actions = [];
    if (me.role === 'SUPER_ADMIN') {
        actions.push(h('a', { class: 'btn', href: '#/classes?new=1' }, icon('class', { small: true }), h('span', null, 'Thêm nội dung kiểm tra')),
            h('a', { class: 'btn btn-secondary', href: '#/users/new' }, icon('user', { small: true }), h('span', null, 'Thêm người dùng')));
    } else if (me.role === 'ADMIN') {
        actions.push(h('a', { class: 'btn', href: '#/requests/new' }, icon('inbox', { small: true }), h('span', null, 'Tạo đề nghị')),
            h('a', { class: 'btn btn-secondary', href: '#/users/new' }, icon('user', { small: true }), h('span', null, 'Thêm người dùng')));
    } else {
        actions.push(h('a', { class: 'btn', href: '#/me' }, icon('user', { small: true }), h('span', null, 'Hồ sơ và điểm của tôi')));
    }

    replace(container,
        pageHead(`Xin chào, ${me.displayName}`,
            [roleLabel(me), me.unit ? me.unit.name : null, `Hôm nay ${formatDate(new Date().toISOString())}`]
                .filter(Boolean).join(' · '),
            actions),
        h('div', { class: 'stat-grid' }, stats),
        ungradedAlert(value(ungraded, (v) => v.total)),
        manager ? dashCharts(value(overview, (v) => v), value(unitOverview, (v) => v), sa) : null,
        h('div', { class: 'dash-grid' },
            isPerson && !manager ? myClassesCard(mine) : recentClassesCard(value(classes, (v) => v.items)),
            noticesCard(value(notices, (v) => v.items))));
}

// ------------------------------------------------------------------ biểu đồ (Admin, Super Admin)

/** Liên kết "xem thêm" ở chân thẻ dashboard. */
function moreLink(href, text) {
    return h('a', { class: 'dash-more', href }, text);
}

function dashCharts(o, unitO, sa) {
    if (!o) {
        return h('div', { class: 'alert alert-error', role: 'alert' }, h('p', null, 'Không tải được số liệu thống kê.'));
    }
    const t = o.totals;
    const units = [...o.byUnit].sort((a, b) => b.counts.total - a.counts.total).slice(0, DASH_UNITS);
    const periods = [...o.byPeriod].reverse().filter((p) => p.counts.tested > 0);
    const contents = o.byContent.filter((x) => x.counts.tested > 0).slice(0, DASH_CONTENTS);
    const u = unitO?.totals;

    // Điểm cá nhân và điểm tập thể đơn vị là hai thẻ riêng, không bao giờ gộp số liệu (CLAUDE.md, ARCHITECTURE S7).
    const resultCard = (title, c, unitWord, more, label) => chartCard(title,
        `${c.total} lượt ${unitWord} · ${c.tested} đã có điểm`,
        donutChart({ series: GRADE_SERIES, counts: c, label,
            center: c.tested ? percentText(passed(c), c.tested) : '—', centerSub: 'đạt trở lên' }),
        h('dl', { class: 'chart-facts' },
            h('div', null, h('dt', null, 'Điểm trung bình'), h('dd', null, avgText(c.avgX10))),
            h('div', null, h('dt', null, 'Không kiểm tra'), h('dd', null, String(c.absent))),
            h('div', null, h('dt', null, 'Chưa kiểm tra'), h('dd', null, String(c.notTested)))),
        more);
    const donutCard = resultCard('Kết quả kiểm tra cá nhân', t, 'người học', moreLink('#/stats', 'Xem thống kê cá nhân'),
        'Phân bố xếp loại điểm cá nhân');
    const unitDonutCard = u
        ? resultCard('Kết quả kiểm tra tập thể đơn vị', u, 'đơn vị', moreLink('#/stats?kind=UNIT', 'Xem thống kê tập thể đơn vị'),
            'Phân bố xếp loại điểm tập thể đơn vị')
        : chartCard('Kết quả kiểm tra tập thể đơn vị', null,
            h('p', { class: 'chart-empty' }, 'Không tải được số liệu điểm tập thể đơn vị.'));
    unitDonutCard.classList.add('dash-unit-result');

    const unitCard = chartCard(sa ? 'Xếp loại theo đơn vị cấp 1' : 'Xếp loại theo đơn vị',
        o.byUnit.length > DASH_UNITS ? `${DASH_UNITS} đơn vị đông người học nhất` : 'Theo đơn vị lúc tham gia',
        stackedBars({ rows: units.map((r) => ({ label: r.name, counts: r.counts })),
            series: [...GRADE_SERIES, ...MISSING_SERIES], label: 'Xếp loại theo đơn vị',
            emptyText: 'Chưa có người học nào.' }),
        moreLink('#/stats', 'Xem bảng đầy đủ'));

    const periodCard = chartCard('Điểm trung bình theo đợt', 'Các đợt gần nhất có điểm, thang điểm 10',
        columnChart({ items: periods.map((p) => ({ label: p.name, value: p.counts.avgX10, text: avgText(p.counts.avgX10),
            tip: `${p.name}: trung bình ${avgText(p.counts.avgX10)}, ${p.counts.tested} điểm, đạt `
                + `${percentText(passed(p.counts), p.counts.tested)}` })),
        max: 100, ticks: [0, 25, 50, 75, 100], tickText: (v) => String(v / 10), label: 'Điểm trung bình theo đợt', height: 260,
        emptyText: 'Chưa có đợt nào có điểm.' }),
        sa ? moreLink('#/periods', 'Đợt kiểm tra') : null);

    const periodGradeCard = chartCard('Xếp loại theo đợt kiểm tra', 'Các đợt gần nhất có điểm',
        stackedBars({ rows: periods.map((p) => ({ label: p.name, counts: p.counts })),
            series: [...GRADE_SERIES, ...MISSING_SERIES], label: 'Xếp loại theo đợt kiểm tra',
            emptyText: 'Chưa có đợt nào có điểm.' }));

    const contentCard = chartCard('Tỷ lệ đạt theo nội dung kiểm tra', 'Các nội dung gần nhất có điểm, đạt trở lên trên số đã có điểm',
        barList({ items: contents.map((x) => ({ label: x.name, sub: `${x.counts.tested} điểm`,
            value: percent(passed(x.counts), x.counts.tested), text: percentText(passed(x.counts), x.counts.tested) })),
        max: 100, label: 'Tỷ lệ đạt theo nội dung kiểm tra', emptyText: 'Chưa có nội dung kiểm tra nào có điểm.' }));

    return h('div', { class: 'dash-charts' }, donutCard, unitDonutCard, unitCard, periodGradeCard, periodCard, contentCard);
}


/** Dòng nhắc giám khảo, Super Admin, Admin: số nội dung còn người chưa chấm (trong phạm vi), mở danh sách đã lọc. */
function ungradedAlert(total) {
    if (!total) return null;
    return h('a', { class: 'alert alert-warning dash-alert', href: '#/classes?ungraded=true' },
        icon('alert', { small: true }), h('span', null, `${total} nội dung kiểm tra chưa chấm xong. Bấm để xem danh sách.`));
}

/**
 * Thẻ danh sách ngắn ở cuối trang chủ. items là null khi không tải được.
 * @param {{failText?: string, emptyText: string, more: [string, string]}} o
 */
function listCard(title, items, { failText, emptyText, more }, toRow) {
    const empty = (text) => h('li', { class: 'dash-row text-muted' }, text);
    return h('section', { class: 'card' },
        h('h2', null, title),
        h('ul', { class: 'dash-list' }, items === null ? empty(failText)
            : items.length === 0 ? empty(emptyText)
                : items.map((it) => h('li', null, toRow(it)))),
        h('a', { class: 'dash-more', href: more[0] }, more[1]));
}

/** Một dòng của thẻ danh sách: biểu tượng tròn, tiêu đề, dòng phụ, nhãn bên phải. */
function dashRow(href, { iconName, tone = null }, title, sub, badge) {
    return h('a', { class: 'dash-row', href },
        h('span', { class: ['stat-icon', tone], 'aria-hidden': 'true' }, icon(iconName, { small: true })),
        h('span', { class: 'dash-text' }, h('span', { class: 'dash-title' }, title), h('span', { class: 'cell-sub' }, sub)),
        badge);
}

/** Nội dung gần đây; nội dung còn người chưa chấm có nhãn cam "Còn N chưa chấm". */
function recentClassesCard(items) {
    return listCard('Nội dung kiểm tra gần đây', items, {
        failText: 'Không tải được danh sách nội dung kiểm tra.',
        emptyText: 'Chưa có nội dung kiểm tra nào.',
        more: ['#/classes', 'Xem tất cả nội dung kiểm tra'],
    }, (c) => {
        const st = OPEN_STATUS[c.status] || { label: c.status, badge: 'badge' };
        return dashRow(`#/classes/${c.id}`, { iconName: 'class' }, c.name,
            [c.period?.name, formatDate(c.examDate)].filter(Boolean).join(' · '),
            c.pendingCount > 0
                ? h('span', { class: 'badge badge-warning' }, `Còn ${c.pendingCount} chưa chấm`)
                : h('span', { class: st.badge }, st.label));
    });
}

function myClassesCard(items) {
    return listCard('Nội dung kiểm tra của tôi', items.slice(0, RECENT), {
        emptyText: 'Bạn chưa tham gia nội dung kiểm tra nào.',
        more: ['#/me', 'Xem hồ sơ và tất cả điểm'],
    }, (c) => dashRow(`#/classes/${c.classInfo.id}`, { iconName: 'class' }, c.classInfo.name,
        [c.classInfo.periodName, formatDate(c.classInfo.examDate)].filter(Boolean).join(' · '),
        c.result ? scoreWithGrade(c.result.scoreX10, c.result.grade) : stateBadge(c.state)));
}

function noticesCard(items) {
    return listCard('Thông báo mới', items, {
        failText: 'Không tải được thông báo.',
        emptyText: 'Chưa có thông báo nào.',
        more: ['#/notifications', 'Xem tất cả thông báo'],
    }, (n) => dashRow('#' + (notificationTarget(n) || '/notifications'),
        { iconName: 'bell', tone: n.readAt ? null : 'is-warning' }, n.title, formatDateTime(n.createdAt),
        n.readAt ? null : h('span', { class: 'badge badge-warning' }, 'Mới')));
}
