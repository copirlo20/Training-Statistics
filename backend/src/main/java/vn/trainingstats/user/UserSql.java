package vn.trainingstats.user;

import java.util.List;
import vn.trainingstats.security.SqlCondition;

/** Mảnh SQL dùng chung khi các tính năng khác (lớp, người học, đề nghị) cần hiển thị hoặc lọc người dùng. */
public final class UserSql {

    private UserSql() {
    }

    /**
     * Họ tên hiển thị: người thì "họ đệm tên", bản ghi đại diện đơn vị (kind = UNIT) thì tên đơn vị.
     *
     * @param u  bí danh bảng app_user
     * @param un bí danh bảng unit đã JOIN theo {@code u.unit_id}
     */
    public static String fullName(String u, String un) {
        return "CASE WHEN " + u + ".kind = 'UNIT' THEN " + un + ".name ELSE TRIM(COALESCE(" + u + ".last_name, '') || ' ' || COALESCE("
                + u + ".first_name, '')) END";
    }

    /** Người dùng ở cột {@code userIdColumn} có ít nhất một trong các chuyên ngành {@code specialtyIds}. */
    public static SqlCondition hasAnySpecialty(String userIdColumn, List<Long> specialtyIds) {
        SqlCondition in = SqlCondition.in("us.specialty_id", specialtyIds);
        return new SqlCondition("EXISTS (SELECT 1 FROM user_specialty us WHERE us.user_id = " + userIdColumn + " AND "
                + in.sql() + ")", in.params());
    }
}
