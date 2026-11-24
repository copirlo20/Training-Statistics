package vn.trainingstats.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vn.trainingstats.common.ApiException;

/** Giáo viên là gán theo từng lớp (class_teacher), không phải vai trò tài khoản. Super Admin luôn có quyền. */
@Service
public class ClassAccess {

    private final JdbcTemplate jdbc;

    public ClassAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isTeacher(long userId, long classId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM class_teacher t
                JOIN exam_class c ON c.id = t.class_id
                WHERE t.user_id = ? AND t.class_id = ? AND c.deleted_at IS NULL
                """, Integer.class, userId, classId);
        return n != null && n > 0;
    }

    public boolean canManage(UserPrincipal principal, long classId) {
        return principal.isSuperAdmin() || isTeacher(principal.userId(), classId);
    }

    /** Không phải giáo viên của lớp thì trả 404 như thể lớp không tồn tại (không tiết lộ lớp của người khác). */
    public void requireManage(UserPrincipal principal, long classId) {
        if (!canManage(principal, classId)) {
            throw ApiException.notFound("Không tìm thấy nội dung kiểm tra.");
        }
    }
}
