import { currentQuery } from '../core/router.js';
import { h, replace } from './escape.js';
import { filterField } from './filter-field.js';
import { createImporter } from './importer.js';

/** Đợt chọn sẵn: periodId trên địa chỉ trang nếu có trong danh sách, không thì đợt đầu tiên ('' khi danh sách rỗng). */
export function periodFromQuery(periods) {
    const wanted = currentQuery().get('periodId');
    if (periods.some((p) => String(p.id) === wanted)) return wanted;
    return periods[0] ? String(periods[0].id) : '';
}

/**
 * Ô chọn đợt kiểm tra trên thanh lọc và bộ nhập Excel của đợt đang chọn; đổi đợt thì dựng lại bộ nhập.
 * @param {{id: string, periods: {id: number, name: string}[], periodId: string, emptyText: string,
 *          optionLabel?: (p) => string, importer: (periodId: string) => object, onChange?: (periodId: string) => void}} o
 *   importer trả cấu hình của createImporter cho một đợt; emptyText hiện thay bộ nhập khi không có đợt nào.
 * @returns {HTMLElement[]} thanh lọc và vùng bộ nhập, đặt ngay dưới đầu trang
 */
export function periodImporter({ id, periods, periodId, emptyText, optionLabel = (p) => p.name, importer, onChange }) {
    if (periods.length === 0) return [h('div', { class: 'alert alert-info' }, h('p', null, emptyText))];
    const select = h('select', { id, class: 'select' },
        periods.map((p) => h('option', { value: String(p.id) }, optionLabel(p))));
    select.value = periodId;
    const slot = h('div');
    const draw = () => replace(slot, createImporter(importer(select.value)).element);
    select.addEventListener('change', () => {
        onChange?.(select.value);
        draw();
    });
    draw();
    return [h('div', { class: 'toolbar toolbar-dense' }, filterField(id, 'Đợt kiểm tra', select)), slot];
}
