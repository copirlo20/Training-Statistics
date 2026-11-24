package vn.trainingstats.exam;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.trainingstats.common.PageQuery;
import vn.trainingstats.common.PageResult;
import vn.trainingstats.exam.ClassDtos.ClassListItem;
import vn.trainingstats.exam.ClassDtos.OpenClass;
import vn.trainingstats.exam.ClassDtos.PeriodRef;
import vn.trainingstats.exam.ClassDtos.PersonRef;
import vn.trainingstats.security.Scope;
import vn.trainingstats.security.SqlCondition;
import vn.trainingstats.security.UserPrincipal;
import vn.trainingstats.user.UserSql;

/**
 * Truy vấn lớp. Lớp đã xóa mềm không bao giờ được trả về.
 * Quyền xem lớp (ARCHITECTURE 3.4): Super Admin tất cả; giáo viên lớp mình dạy; người học lớp mình tham gia;
 * Admin mọi lớp (số người học chỉ đếm người trong nhánh, theo đơn vị HIỆN TẠI).
 */
@Repository
public class ClassRepository {

    /**
     * Dữ liệu thô để kiểm quyền và trạng thái trước khi thao tác. {@code status} là trạng thái lưu của lớp;
     * {@code periodClosed}: đợt đã đóng, khi đó lớp coi như đã đóng ({@link ClassStatusSql}).
     */
    public record ClassRow(long id, String name, String status, long periodId, int version, boolean periodClosed) {
        public boolean open() {
            return "OPEN".equals(status) && !periodClosed;
        }
    }

    private static final String STATUS = ClassStatusSql.effective("c", "p");

    /** Đợt của dòng, từ các cột period_id, period_name, period_status. */
    private static PeriodRef periodRef(ResultSet rs) throws SQLException {
        return new PeriodRef(rs.getLong("period_id"), rs.getString("period_name"), rs.getString("period_status"));
    }

    /** {@code ungradedOnly}: chỉ lớp còn người học (trong phạm vi đếm) chưa có điểm và chưa ghi lý do vắng. */
    public record Filter(Long periodId, String status, boolean teachingOnly, boolean ungradedOnly) {
    }

    private final JdbcTemplate jdbc;

    public ClassRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ClassRow> findRow(long id) {
        return jdbc.query("""
                SELECT c.id, c.name, c.status, c.period_id, c.version, p.status AS period_status
                FROM exam_class c JOIN exam_period p ON p.id = c.period_id
                WHERE c.id = ? AND c.deleted_at IS NULL
                """, (rs, i) -> new ClassRow(rs.getLong("id"), rs.getString("name"), rs.getString("status"),
                rs.getLong("period_id"), rs.getInt("version"), "CLOSED".equals(rs.getString("period_status"))), id)
                .stream().findFirst();
    }

    public boolean isParticipant(long classId, long userId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM class_participant WHERE class_id = ? AND user_id = ? AND removed_at IS NULL
                """, Integer.class, classId, userId);
        return n != null && n > 0;
    }

    public PageResult<ClassListItem> search(UserPrincipal me, Scope scope, Filter f, PageQuery page) {
        StringBuilder where = new StringBuilder(" WHERE c.deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        if (!me.isSuperAdmin() && !me.isAdmin()) {
            SqlCondition branch = scope.unitFilter("bun.code");
            where.append(" AND ").append("""
                    (EXISTS (SELECT 1 FROM class_teacher t WHERE t.class_id = c.id AND t.user_id = ?)
                       OR EXISTS (SELECT 1 FROM class_participant sp WHERE sp.class_id = c.id AND sp.user_id = ?
                                  AND sp.removed_at IS NULL)
                       OR EXISTS (SELECT 1 FROM class_participant bp JOIN app_user bu ON bu.id = bp.user_id
                                  JOIN unit bun ON bun.id = bu.unit_id
                                  WHERE bp.class_id = c.id AND bp.removed_at IS NULL AND\s""")
                    .append(branch.sql()).append("))");
            params.add(me.userId());
            params.add(me.userId());
            params.addAll(branch.params());
        }
        if (f.teachingOnly()) {
            where.append(" AND EXISTS (SELECT 1 FROM class_teacher t2 WHERE t2.class_id = c.id AND t2.user_id = ?)");
            params.add(me.userId());
        }
        if (f.periodId() != null) {
            where.append(" AND c.period_id = ?");
            params.add(f.periodId());
        }
        if (f.status() != null) {
            where.append(" AND ").append(STATUS).append(" = ?");
            params.add(f.status());
        }
        if (f.ungradedOnly()) {
            Counted pending = counted(me, scope, PENDING);
            where.append(" AND ").append(pending.sql()).append(" > 0");
            params.addAll(pending.params());
        }
        String from = " FROM exam_class c JOIN exam_period p ON p.id = c.period_id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, params.toArray());

        Counted all = counted(me, scope, "");
        Counted graded = counted(me, scope, GRADED);
        Counted pending = counted(me, scope, PENDING);
        List<Object> selectParams = new ArrayList<>();
        selectParams.add(me.userId());
        selectParams.addAll(all.params());
        selectParams.addAll(graded.params());
        selectParams.addAll(pending.params());
        selectParams.addAll(params);
        selectParams.add(page.size());
        selectParams.add(page.offset());
        List<ClassListItem> items = jdbc.query("""
                SELECT c.id, c.name, c.exam_date, c.location, """ + STATUS + """
                 AS status,
                       p.id AS period_id, p.name AS period_name, p.status AS period_status,
                       EXISTS (SELECT 1 FROM class_teacher t WHERE t.class_id = c.id AND t.user_id = ?) AS teaching,
                       """ + all.sql() + " AS participant_count, " + graded.sql() + " AS graded_count, "
                        + pending.sql() + " AS pending_count"
                        + from + where + " ORDER BY (c.exam_date IS NULL), c.exam_date DESC, c.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new ClassListItem(rs.getLong("id"), rs.getString("name"),
                        periodRef(rs),
                        rs.getString("exam_date"), rs.getString("location"), rs.getString("status"),
                        rs.getInt("participant_count"), rs.getInt("graded_count"), rs.getInt("pending_count"),
                        rs.getInt("teaching") == 1),
                selectParams.toArray());
        return page.result(items, total == null ? 0 : total);
    }

    /** Người học đã có điểm. */
    private static final String GRADED = " AND EXISTS (SELECT 1 FROM result vr WHERE vr.participant_id = vp.id)";
    /** Người học chưa có điểm và chưa ghi lý do vắng: còn phải chấm. */
    private static final String PENDING = " AND NOT EXISTS (SELECT 1 FROM result vr WHERE vr.participant_id = vp.id)"
            + " AND vp.absence_reason_id IS NULL";

    private record Counted(String sql, List<Object> params) {
    }

    /**
     * Biểu thức đếm người học (chưa gỡ) của lớp {@code c} mà người gọi được thấy: cả lớp nếu là Super Admin hoặc giáo
     * viên của lớp, nếu không thì theo phạm vi (Admin: nhánh mình; người dùng: chính mình). {@code extra} lọc thêm.
     */
    private static Counted counted(UserPrincipal me, Scope scope, String extra) {
        SqlCondition visible = scope.userFilter("vp.user_id", "vun.code");
        List<Object> params = new ArrayList<>();
        params.add(me.isSuperAdmin() ? 1 : 0);
        params.add(me.userId());
        params.addAll(visible.params());
        return new Counted("""
                (SELECT COUNT(*) FROM class_participant vp JOIN app_user vu ON vu.id = vp.user_id
                 LEFT JOIN unit vun ON vun.id = vu.unit_id
                 WHERE vp.class_id = c.id AND vp.removed_at IS NULL
                   AND (? = 1 OR EXISTS (SELECT 1 FROM class_teacher vt WHERE vt.class_id = c.id AND vt.user_id = ?)
                        OR\s""" + visible.sql() + ")" + extra + ")", params);
    }

    public List<OpenClass> openClasses() {
        return jdbc.query("""
                SELECT c.id, c.name, c.exam_date, p.name AS period_name
                FROM exam_class c JOIN exam_period p ON p.id = c.period_id
                WHERE c.deleted_at IS NULL AND c.status = 'OPEN' AND p.status = 'OPEN'
                ORDER BY (c.exam_date IS NULL), c.exam_date DESC, c.id DESC
                """, (rs, i) -> new OpenClass(rs.getLong("id"), rs.getString("name"), rs.getString("period_name"),
                rs.getString("exam_date")));
    }

    /** Mọi nội dung chưa xóa của một đợt, cả nội dung đã đóng (vẫn chấm điểm được), cùng thứ tự với bảng phân công. */
    public List<OpenClass> inPeriod(long periodId) {
        return jdbc.query("""
                SELECT c.id, c.name, c.exam_date, p.name AS period_name
                FROM exam_class c JOIN exam_period p ON p.id = c.period_id
                WHERE c.period_id = ? AND c.deleted_at IS NULL
                ORDER BY (c.exam_date IS NULL), c.exam_date, c.id
                """, (rs, i) -> new OpenClass(rs.getLong("id"), rs.getString("name"), rs.getString("period_name"),
                rs.getString("exam_date")), periodId);
    }

    /**
     * Thông tin lớp không kèm quyền và danh sách giáo viên (service ghép thêm). {@code status} là trạng thái lưu (dùng
     * cho nhật ký); trạng thái hiệu lực xem {@link #effectiveStatus()}.
     */
    public record Detail(long id, String name, PeriodRef period, String examDate, String location,
                         String note, String status, int version) {
        public String effectiveStatus() {
            return "CLOSED".equals(period.status()) ? "CLOSED" : status;
        }
    }

    public Optional<Detail> findDetail(long id) {
        return jdbc.query("""
                SELECT c.id, c.name, c.exam_date, c.location, c.note, c.status, c.version,
                       p.id AS period_id, p.name AS period_name, p.status AS period_status
                FROM exam_class c JOIN exam_period p ON p.id = c.period_id
                WHERE c.id = ? AND c.deleted_at IS NULL
                """, (rs, i) -> new Detail(rs.getLong("id"), rs.getString("name"),
                periodRef(rs),
                rs.getString("exam_date"), rs.getString("location"), rs.getString("note"), rs.getString("status"),
                rs.getInt("version")), id).stream().findFirst();
    }

    public List<PersonRef> teachers(long classId) {
        return jdbc.query("SELECT u.id, " + UserSql.fullName("u", "un") + """
                 AS full_name, r.symbol AS rank_symbol, un.name AS unit_name
                FROM class_teacher t
                JOIN app_user u ON u.id = t.user_id
                LEFT JOIN unit un ON un.id = u.unit_id
                LEFT JOIN rank r ON r.id = u.rank_id
                WHERE t.class_id = ?
                ORDER BY u.first_name, u.last_name, u.id
                """, (rs, i) -> new PersonRef(rs.getLong("id"), rs.getString("full_name"), rs.getString("rank_symbol"),
                rs.getString("unit_name")), classId);
    }

    public List<Long> teacherIds(long classId) {
        return jdbc.queryForList("SELECT user_id FROM class_teacher WHERE class_id = ? ORDER BY user_id", Long.class, classId);
    }

    public int countActiveParticipants(long classId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_participant WHERE class_id = ? AND removed_at IS NULL", Integer.class, classId);
        return n == null ? 0 : n;
    }

    public int countParticipantsVisible(long classId, Scope scope) {
        SqlCondition c = scope.userFilter("cp.user_id", "un.code");
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM class_participant cp
                JOIN app_user u ON u.id = cp.user_id
                LEFT JOIN unit un ON un.id = u.unit_id
                WHERE cp.class_id = ? AND cp.removed_at IS NULL AND\s""" + c.sql(), Integer.class, c.paramsAfter(classId));
        return n == null ? 0 : n;
    }

    public int countResults(long classId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM result r JOIN class_participant cp ON cp.id = r.participant_id
                WHERE cp.class_id = ?
                """, Integer.class, classId);
        return n == null ? 0 : n;
    }
}
