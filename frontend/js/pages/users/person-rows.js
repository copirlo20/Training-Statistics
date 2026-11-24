import { formatDate } from '../../core/format.js';
import { CATEGORY } from '../../core/refdata.js';
import { dlRow } from '../../ui/dl.js';
import { h } from '../../ui/escape.js';

/** Các dòng thông tin cá nhân dùng chung cho trang Hồ sơ và trang chi tiết người dùng. */
export function personRows(user) {
    const category = CATEGORY[user.category];
    return [
        dlRow('Mã định danh', user.personalCode && h('span', { class: 'mono' }, user.personalCode)),
        dlRow('Đối tượng', category ? `${category.short} - ${category.label}` : null),
        dlRow('Cấp bậc', user.rank && `${user.rank.name} (${user.rank.symbol})`),
        dlRow('Chức vụ', user.position?.name),
        dlRow('Đơn vị', user.unit ? `${user.unit.path} (${user.unit.code})` : 'Không thuộc đơn vị (Super Admin)'),
        dlRow('Chuyên ngành', user.specialties.map((s) => s.name).join(', ')),
        dlRow('Ngày sinh', formatDate(user.birthDate)),
    ];
}
