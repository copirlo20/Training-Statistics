package vn.trainingstats.assignment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.trainingstats.assignment.AssignmentDtos.UnitOption;
import vn.trainingstats.common.PageQuery;
import vn.trainingstats.common.VietnameseText;
import vn.trainingstats.exam.ClassStatusSql;
import vn.trainingstats.security.Scope;
import vn.trainingstats.security.SqlCondition;
import vn.trainingstats.user.UserSql;

/**
 * Truy vấn cho bảng phân công. Danh sách người luôn ghép điều kiện phạm vi của {@link Scope}: Admin chỉ thấy người
 * thuộc nhánh mình, Super Admin và giám khảo (được service đổi sang {@code Scope.all}) thấy toàn Lữ đoàn.
 * Chỉ lấy các trường được phép lộ: tên, cấp bậc, đơn vị, mã định danh, chuyên ngành.
 */
@Repository
public class AssignmentRepository {

    /** Trạng thái hiệu lực của nội dung: đợt đã đóng thì coi như đóng. */
    private static final String STATUS = ClassStatusSql.effective("c", "p");

    public record ContentRow(long id, String name, String examDate, String location, String status) {
    }

    public record PersonRow(long userId, String fullName, String rankSymbol, String rankName, String personalCode,
                            String category, long unitId, String unitCode, String unitName, String parentUnitName) {
    }

    /**
     * {@code unitCodes}: gốc các nhánh cần lọc (gồm đơn vị con), rỗng là không lọc. {@code exactUnitId}: chỉ người
     * thuộc đúng đơn vị này (không gồm đơn vị con), null là không lọc. Chuyên ngành: có ít nhất một trong danh sách.
     * {@code categories}: đối tượng (SQ, QNCN, HSQ-CS), rỗng là không lọc.
     */
    public record Filter(List<String> unitCodes, Long exactUnitId, List<Long> specialtyIds, List<String> categories,
                         String q) {
    }

    public record Participation(long classId, long userId, boolean graded) {
    }

    public record Pending(long classId, long userId, String type, long requestedBy) {
    }

    private final JdbcTemplate jdbc;

    public AssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isTeacherInPeriod(long userId, long periodId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM class_teacher t JOIN exam_class c ON c.id = t.class_id
                WHERE t.user_id = ? AND c.period_id = ? AND c.deleted_at IS NULL
                """, Integer.class, userId, periodId);
        return n != null && n > 0;
    }

    public List<Long> teachingClassIds(long userId, Collection<Long> classIds) {
        if (classIds.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        params.add(userId);
        params.addAll(classIds);
        return jdbc.queryForList("SELECT class_id FROM class_teacher WHERE user_id = ? AND class_id IN (" + SqlCondition.marks(classIds.size()) + ")",
                Long.class, params.toArray());
    }

    /**
     * Nội dung kiểm tra của đợt mà người gọi thao tác hoặc gửi đề nghị được. Super Admin: tất cả. Giám khảo: nội dung
     * mình được gán. Admin: thêm mọi nội dung đang mở (để gửi đề nghị), và nội dung đã đóng chỉ khi có người thuộc nhánh mình.
     */
    public List<ContentRow> contents(long periodId, boolean includeClosed, long userId, boolean superAdmin,
                                     Scope adminScope) {
        List<Object> params = new ArrayList<>();
        params.add(periodId);
        StringBuilder visible = new StringBuilder();
        if (superAdmin) {
            visible.append("1 = 1");
        } else {
            visible.append("EXISTS (SELECT 1 FROM class_teacher t WHERE t.class_id = c.id AND t.user_id = ?)");
            params.add(userId);
            if (adminScope != null) {
                visible.append(" OR ").append(STATUS).append(" = 'OPEN'");
                if (includeClosed) {
                    SqlCondition vis = adminScope.userFilter("cp.user_id", "vun.code");
                    visible.append(" OR EXISTS (SELECT 1 FROM class_participant cp JOIN app_user vu ON vu.id = cp.user_id"
                            + " LEFT JOIN unit vun ON vun.id = vu.unit_id WHERE cp.class_id = c.id AND cp.removed_at IS NULL AND ")
                            .append(vis.sql()).append(")");
                    params.addAll(vis.params());
                }
            }
        }
        return jdbc.query("""
                SELECT c.id, c.name, c.exam_date, c.location,\s""" + STATUS + """
                 AS status FROM exam_class c JOIN exam_period p ON p.id = c.period_id
                WHERE c.period_id = ? AND c.deleted_at IS NULL""" + (includeClosed ? "" : " AND " + STATUS + " = 'OPEN'")
                        + " AND (" + visible + ") ORDER BY (c.exam_date IS NULL), c.exam_date, c.id",
                (rs, i) -> new ContentRow(rs.getLong("id"), rs.getString("name"), rs.getString("exam_date"),
                        rs.getString("location"), rs.getString("status")), params.toArray());
    }

    /** Một trang người, theo thứ tự cây đơn vị rồi theo tên. Trả kèm tổng số. */
    public Page people(Scope scope, Filter f, PageQuery page) {
        SqlCondition sc = scope.userFilter("u.id", "un.code");
        StringBuilder where = new StringBuilder("""
                 FROM app_user u
                JOIN unit un ON un.id = u.unit_id
                LEFT JOIN unit pu ON pu.id = un.parent_id
                LEFT JOIN rank r ON r.id = u.rank_id
                WHERE u.kind = 'PERSON' AND u.status = 'ACTIVE' AND u.role <> 'SUPER_ADMIN' AND\s""");
        where.append(sc.sql());
        List<Object> params = new ArrayList<>(sc.params());
        if (!f.unitCodes().isEmpty()) {
            SqlCondition.withinAny("un.code", f.unitCodes()).appendTo(where, params);
        }
        if (f.exactUnitId() != null) {
            where.append(" AND u.unit_id = ?");
            params.add(f.exactUnitId());
        }
        if (!f.categories().isEmpty()) {
            SqlCondition.in("u.category", f.categories()).appendTo(where, params);
        }
        if (!f.specialtyIds().isEmpty()) {
            UserSql.hasAnySpecialty("u.id", f.specialtyIds()).appendTo(where, params);
        }
        String key = f.q() == null ? "" : VietnameseText.searchKey(f.q());
        if (!key.isEmpty()) {
            where.append(" AND u.search_text LIKE ? ESCAPE '\\'");
            params.add("%" + VietnameseText.escapeLike(key) + "%");
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(page.size());
        pageParams.add(page.offset());
        List<PersonRow> rows = jdbc.query("SELECT u.id, " + UserSql.fullName("u", "un") + " AS full_name, r.symbol AS rank_symbol,"
                        + " r.name AS rank_name, u.personal_code, u.category,"
                        + " un.id AS unit_id, un.code AS unit_code, un.name AS unit_name, pu.name AS parent_name"
                        + where + " ORDER BY un.sort_order, un.code, u.first_name COLLATE NOCASE, u.last_name COLLATE NOCASE, u.id"
                        + " LIMIT ? OFFSET ?",
                (rs, i) -> new PersonRow(rs.getLong("id"), rs.getString("full_name"), rs.getString("rank_symbol"),
                        rs.getString("rank_name"), rs.getString("personal_code"), rs.getString("category"),
                        rs.getLong("unit_id"), rs.getString("unit_code"),
                        rs.getString("unit_name"), rs.getString("parent_name")), pageParams.toArray());
        return new Page(rows, total == null ? 0 : total);
    }

    public record Page(List<PersonRow> rows, long total) {
    }

    public Map<Long, List<String>> specialties(Collection<Long> userIds) {
        Map<Long, List<String>> out = new HashMap<>();
        if (userIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT us.user_id, s.name FROM user_specialty us JOIN specialty s ON s.id = us.specialty_id"
                        + " WHERE us.user_id IN (" + SqlCondition.marks(userIds.size()) + ") ORDER BY s.sort_order, s.id",
                rs -> {
                    out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
                }, userIds.toArray());
        return out;
    }

    public List<Participation> participations(Collection<Long> classIds, Collection<Long> userIds) {
        if (classIds.isEmpty() || userIds.isEmpty()) {
            return List.of();
        }
        SqlCondition c = classesAndUsers("cp.", classIds, userIds);
        return jdbc.query("""
                SELECT cp.class_id, cp.user_id, EXISTS (SELECT 1 FROM result r WHERE r.participant_id = cp.id) AS graded
                FROM class_participant cp
                WHERE cp.removed_at IS NULL AND\s""" + c.sql(),
                (rs, i) -> new Participation(rs.getLong(1), rs.getLong(2), rs.getInt(3) == 1), c.params().toArray());
    }

    public List<Pending> pending(Collection<Long> classIds, Collection<Long> userIds) {
        if (classIds.isEmpty() || userIds.isEmpty()) {
            return List.of();
        }
        SqlCondition c = classesAndUsers("", classIds, userIds);
        return jdbc.query("SELECT class_id, user_id, type, requested_by FROM class_request WHERE status = 'PENDING' AND " + c.sql(),
                (rs, i) -> new Pending(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4)), c.params().toArray());
    }

    private static SqlCondition classesAndUsers(String alias, Collection<Long> classIds, Collection<Long> userIds) {
        return SqlCondition.in(alias + "class_id", classIds).and(SqlCondition.in(alias + "user_id", userIds));
    }

    /** Mọi người học (chưa gỡ) của các nội dung: dùng khi nhập file, người trong file có thể rất nhiều. */
    public List<Participation> participationsIn(Collection<Long> classIds) {
        if (classIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT cp.class_id, cp.user_id, EXISTS (SELECT 1 FROM result r WHERE r.participant_id = cp.id) AS graded
                FROM class_participant cp WHERE cp.removed_at IS NULL AND cp.class_id IN (""" + SqlCondition.marks(classIds.size()) + ")",
                (rs, i) -> new Participation(rs.getLong(1), rs.getLong(2), rs.getInt(3) == 1), classIds.toArray());
    }

    /** Mọi đề nghị đang chờ của các nội dung. */
    public List<Pending> pendingIn(Collection<Long> classIds) {
        if (classIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT class_id, user_id, type, requested_by FROM class_request WHERE status = 'PENDING'"
                        + " AND class_id IN (" + SqlCondition.marks(classIds.size()) + ")",
                (rs, i) -> new Pending(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4)), classIds.toArray());
    }

    /**
     * Đơn vị đang hoạt động trong phạm vi, theo thứ tự cây, kèm số người thuộc đúng đơn vị đó (không gồm đơn vị con):
     * bảng phân công và file Excel làm theo từng đơn vị một.
     */
    public List<UnitOption> units(Scope scope) {
        SqlCondition sc = scope.unitFilter("un.code");
        return jdbc.query("SELECT un.id, un.code, un.name, un.level, pu.name AS parent_name,"
                        + " (SELECT COUNT(*) FROM app_user m WHERE m.unit_id = un.id AND m.kind = 'PERSON'"
                        + " AND m.status = 'ACTIVE' AND m.role <> 'SUPER_ADMIN') AS people"
                        + " FROM unit un LEFT JOIN unit pu ON pu.id = un.parent_id WHERE un.active = 1 AND " + sc.sql()
                        + " ORDER BY un.sort_order, un.code",
                (rs, i) -> new UnitOption(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getInt("level"), rs.getString("parent_name"), rs.getInt("people")), sc.params().toArray());
    }

}
