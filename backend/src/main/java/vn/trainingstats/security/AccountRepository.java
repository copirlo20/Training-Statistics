package vn.trainingstats.security;

import vn.trainingstats.common.JdbcValues;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Truy vấn tài khoản phục vụ đăng nhập, nạp principal và đổi mật khẩu. */
@Repository
public class AccountRepository {

    /** Dữ liệu cần để kiểm tra đăng nhập. Chỉ dùng trong gói security và auth, không trả ra API. */
    public record LoginAccount(long id, String username, String passwordHash, String status, String kind) {
    }

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LoginAccount> findLogin(String username) {
        // Cột username có COLLATE NOCASE nên so sánh không phân biệt hoa thường.
        List<LoginAccount> rows = jdbc.query(
                "SELECT id, username, password_hash, status, kind FROM app_user WHERE username = ?",
                (rs, i) -> new LoginAccount(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                        rs.getString("status"), rs.getString("kind")),
                username);
        return rows.stream().findFirst();
    }

    /** Principal của người dùng còn đăng nhập được: PERSON, ACTIVE, có tài khoản và mật khẩu. */
    public Optional<UserPrincipal> loadActivePrincipal(long userId) {
        List<UserPrincipal> rows = jdbc.query("""
                SELECT u.id, u.username, u.role, u.unit_id, un.code AS unit_code, un.level AS unit_level,
                       u.last_name, u.first_name, u.must_change_password
                FROM app_user u
                LEFT JOIN unit un ON un.id = u.unit_id
                WHERE u.id = ? AND u.kind = 'PERSON' AND u.status = 'ACTIVE'
                  AND u.username IS NOT NULL AND u.password_hash IS NOT NULL
                """,
                (rs, i) -> new UserPrincipal(
                        rs.getLong("id"),
                        rs.getString("username"),
                        UserPrincipal.Role.valueOf(rs.getString("role")),
                        JdbcValues.nullableLong(rs, "unit_id"),
                        rs.getString("unit_code"),
                        JdbcValues.nullableInt(rs, "unit_level"),
                        (rs.getString("last_name") + " " + rs.getString("first_name")).trim(),
                        rs.getInt("must_change_password") == 1),
                userId);
        return rows.stream().findFirst();
    }

    public Optional<String> findPasswordHash(long userId) {
        List<String> rows = jdbc.queryForList("SELECT password_hash FROM app_user WHERE id = ?", String.class, userId);
        return rows.stream().findFirst();
    }

    public void updateLastLogin(long userId, String at) {
        jdbc.update("UPDATE app_user SET last_login_at = ? WHERE id = ?", at, userId);
    }

    /**
     * Chính chủ đổi mật khẩu (nên bỏ cờ bắt đổi). Chỉ cập nhật nếu hash hiện tại vẫn là hash đã kiểm tra, tránh hai
     * lần đổi mật khẩu chen nhau.
     */
    public int updatePasswordHash(long userId, String expectedOldHash, String newHash, String at) {
        return jdbc.update("""
                UPDATE app_user SET password_hash = ?, must_change_password = 0, updated_at = ?, updated_by = ?,
                       version = version + 1
                WHERE id = ? AND password_hash = ?
                """, newHash, at, userId, userId, expectedOldHash);
    }

    public int countActiveSuperAdminsExcept(long userId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE role = 'SUPER_ADMIN' AND status = 'ACTIVE' AND id <> ?",
                Integer.class, userId);
        return n == null ? 0 : n;
    }

    public boolean isActiveSuperAdmin(long userId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE id = ? AND role = 'SUPER_ADMIN' AND status = 'ACTIVE'",
                Integer.class, userId);
        return n != null && n > 0;
    }
}
