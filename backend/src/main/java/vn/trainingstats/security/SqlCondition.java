package vn.trainingstats.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Một mảnh điều kiện WHERE cùng tham số theo thứ tự dấu ?. Ghép vào câu SQL bằng {@code " AND " + sql()}. */
public record SqlCondition(String sql, List<Object> params) {

    public static final SqlCondition ALWAYS = new SqlCondition("1 = 1", List.of());
    public static final SqlCondition NEVER = new SqlCondition("1 = 0", List.of());

    /** Tham số cho câu có các dấu ? của {@code leading} đứng trước điều kiện này. */
    public Object[] paramsAfter(Object... leading) {
        List<Object> all = new ArrayList<>(Arrays.asList(leading));
        all.addAll(params);
        return all.toArray();
    }

    /** Điều kiện này AND {@code other}, tham số nối theo thứ tự. */
    public SqlCondition and(SqlCondition other) {
        List<Object> all = new ArrayList<>(params);
        all.addAll(other.params);
        return new SqlCondition(sql + " AND " + other.sql, all);
    }

    /** Ghép {@code " AND " + sql()} vào câu đang dựng và thêm tham số theo đúng thứ tự. */
    public void appendTo(StringBuilder where, List<Object> whereParams) {
        where.append(" AND ").append(sql);
        whereParams.addAll(params);
    }

    /** {@code n} dấu ? cách nhau dấu phẩy, dùng trong {@code IN (...)}. */
    public static String marks(int n) {
        return String.join(", ", Collections.nCopies(n, "?"));
    }

    /** {@code column IN (?, ...)}. Danh sách rỗng thì không khớp dòng nào. */
    public static SqlCondition in(String column, Collection<?> values) {
        return values.isEmpty() ? NEVER : new SqlCondition(column + " IN (" + marks(values.size()) + ")", List.copyOf(values));
    }

    /** Thuộc nhánh của một trong các đơn vị {@code roots} (mỗi đơn vị gồm đơn vị con). Danh sách rỗng thì không khớp. */
    public static SqlCondition withinAny(String unitCodeColumn, Collection<String> roots) {
        if (roots.isEmpty()) {
            return NEVER;
        }
        List<String> parts = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (String root : roots) {
            SqlCondition c = UnitCodes.within(unitCodeColumn, root);
            parts.add(c.sql());
            params.addAll(c.params());
        }
        return new SqlCondition("(" + String.join(" OR ", parts) + ")", params);
    }
}
