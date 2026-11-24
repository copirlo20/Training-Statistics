package vn.trainingstats.common;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Đọc cột có thể NULL. Không dùng {@code rs.getObject(col, Long.class)}: sqlite-jdbc ném "Bad value for type Long"
 * khi giá trị là NULL.
 */
public final class JdbcValues {

    private JdbcValues() {
    }

    public static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    public static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }
}
