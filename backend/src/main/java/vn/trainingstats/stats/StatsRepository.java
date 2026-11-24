package vn.trainingstats.stats;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.trainingstats.common.JdbcValues;
import vn.trainingstats.common.PageQuery;
import vn.trainingstats.security.Scope;
import vn.trainingstats.security.SqlCondition;
import vn.trainingstats.stats.StatsDtos.Bucket;
import vn.trainingstats.stats.StatsDtos.Counts;
import vn.trainingstats.stats.StatsDtos.NotTestedItem;
import vn.trainingstats.stats.StatsDtos.PeriodRow;
import vn.trainingstats.stats.StatsDtos.ReasonCount;
import vn.trainingstats.stats.StatsDtos.ContentRow;
import vn.trainingstats.stats.StatsDtos.UnitRow;
import vn.trainingstats.user.UserSql;

/**
 * Truy vấn thống kê (ARCHITECTURE 3.6, 3.12). Quy tắc chung của mọi truy vấn:
 * <ul>
 *   <li>Nhận {@link Scope}: phạm vi ghép trên đơn vị LÚC VÀO LỚP ({@code unit_id_snapshot}, Q4, R2), nên người chuyển
 *       đơn vị về sau không làm đổi số liệu cũ.</li>
 *   <li>Bỏ người đã bị gỡ khỏi lớp (Q9) và lớp đã xóa.</li>
 *   <li>Chỉ một loại người học mỗi lần ({@code kind}: PERSON hoặc UNIT), không trộn (R9).</li>
 *   <li>Xếp loại đếm theo cột {@code result.grade} của DB, không tính lại.</li>
 * </ul>
 */
@Repository
public class StatsRepository {

    /** Bộ lọc đã kiểm. {@code unitCodes}: gốc các nhánh cần lọc (mỗi đơn vị gồm đơn vị con), rỗng là không lọc. */
    public record Filter(String kind, Long periodId, Long classId, List<String> unitCodes, String from, String to) {
    }

    private static final String FROM = """
             FROM class_participant cp
            JOIN exam_class ec ON ec.id = cp.class_id AND ec.deleted_at IS NULL
            JOIN exam_period ep ON ep.id = ec.period_id
            JOIN app_user u ON u.id = cp.user_id
            JOIN unit su ON su.id = cp.unit_id_snapshot
            LEFT JOIN result r ON r.participant_id = cp.id
            """;

    private static final String COUNTS = """
             COUNT(*) AS total, COUNT(r.id) AS tested,
            COUNT(CASE WHEN r.id IS NULL AND cp.absence_reason_id IS NOT NULL THEN 1 END) AS absent,
            COUNT(CASE WHEN r.grade = 'FAIL' THEN 1 END) AS fail,
            COUNT(CASE WHEN r.grade = 'PASS' THEN 1 END) AS pass,
            COUNT(CASE WHEN r.grade = 'GOOD' THEN 1 END) AS good,
            COUNT(CASE WHEN r.grade = 'EXCELLENT' THEN 1 END) AS excellent,
            AVG(r.score_x10) AS avg_x10, MAX(r.score_x10) AS max_x10, MIN(r.score_x10) AS min_x10
            """;

    private final JdbcTemplate jdbc;

    public StatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Where(String sql, List<Object> params) {
    }

    private static Where where(Filter f, Scope scope) {
        StringBuilder sb = new StringBuilder(" WHERE cp.removed_at IS NULL AND u.kind = ?");
        List<Object> params = new ArrayList<>();
        params.add(f.kind());
        SqlCondition sc = scope.unitFilter("su.code");
        sb.append(" AND ").append(sc.sql());
        params.addAll(sc.params());
        if (f.periodId() != null) {
            sb.append(" AND ec.period_id = ?");
            params.add(f.periodId());
        }
        if (f.classId() != null) {
            sb.append(" AND ec.id = ?");
            params.add(f.classId());
        }
        if (f.unitCodes() != null && !f.unitCodes().isEmpty()) {
            SqlCondition.withinAny("su.code", f.unitCodes()).appendTo(sb, params);
        }
        if (f.from() != null) {
            sb.append(" AND ec.exam_date >= ?");
            params.add(f.from());
        }
        if (f.to() != null) {
            sb.append(" AND ec.exam_date <= ?");
            params.add(f.to());
        }
        return new Where(sb.toString(), params);
    }

    static Counts counts(ResultSet rs) throws SQLException {
        long total = rs.getLong("total");
        long tested = rs.getLong("tested");
        long absent = rs.getLong("absent");
        double avg = rs.getDouble("avg_x10");
        Double avgX10 = rs.wasNull() ? null : Math.round(avg * 10) / 10.0;
        return new Counts(total, tested, absent, total - tested - absent, rs.getLong("fail"), rs.getLong("pass"),
                rs.getLong("good"), rs.getLong("excellent"), avgX10, JdbcValues.nullableInt(rs, "max_x10"),
                JdbcValues.nullableInt(rs, "min_x10"));
    }

    public Counts totals(Filter f, Scope scope) {
        Where w = where(f, scope);
        return jdbc.queryForObject("SELECT" + COUNTS + FROM + w.sql(), (rs, i) -> counts(rs), w.params().toArray());
    }

    /**
     * Theo đơn vị lúc vào lớp ({@code top = false}) hoặc gộp về đơn vị cấp 1 ({@code top = true}).
     */
    public List<UnitRow> byUnit(Filter f, Scope scope, boolean top) {
        Where w = where(f, scope);
        String groupId = top ? "COALESCE(su.parent_id, su.id)" : "su.id";
        return jdbc.query("SELECT g.id AS unit_id, g.code, g.name," + COUNTS + FROM
                        + " JOIN unit g ON g.id = " + groupId + w.sql() + " GROUP BY g.id ORDER BY g.sort_order, g.code",
                (rs, i) -> new UnitRow(rs.getLong("unit_id"), rs.getString("code"), rs.getString("name"), counts(rs)),
                w.params().toArray());
    }

    /** Theo đợt, đợt mới nhất trước (theo ngày bắt đầu, rồi id). */
    public List<PeriodRow> byPeriod(Filter f, Scope scope, int limit) {
        Where w = where(f, scope);
        List<Object> params = new ArrayList<>(w.params());
        params.add(limit);
        return jdbc.query("SELECT ep.id AS period_id, ep.name, ep.start_date," + COUNTS + FROM + w.sql()
                        + " GROUP BY ep.id ORDER BY ep.start_date DESC, ep.id DESC LIMIT ?",
                (rs, i) -> new PeriodRow(rs.getLong("period_id"), rs.getString("name"), rs.getString("start_date"),
                        counts(rs)), params.toArray());
    }

    /** Theo nội dung kiểm tra đã có điểm, ngày kiểm tra gần nhất trước (chưa có ngày thì sau cùng), rồi id. */
    public List<ContentRow> byContent(Filter f, Scope scope, int limit) {
        Where w = where(f, scope);
        List<Object> params = new ArrayList<>(w.params());
        params.add(limit);
        return jdbc.query("SELECT ec.id AS class_id, ec.name, ec.exam_date," + COUNTS + FROM + w.sql()
                        + " GROUP BY ec.id HAVING COUNT(r.id) > 0 ORDER BY (ec.exam_date IS NULL), ec.exam_date DESC, ec.id DESC LIMIT ?",
                (rs, i) -> new ContentRow(rs.getLong("class_id"), rs.getString("name"), rs.getString("exam_date"),
                        counts(rs)), params.toArray());
    }

    /** Phân bố điểm theo 10 khoảng 1 điểm: [0,1), [1,2)... [9,10]. */
    public List<Bucket> histogram(Filter f, Scope scope) {
        Where w = where(f, scope);
        long[] counts = new long[10];
        jdbc.query("SELECT MIN(r.score_x10 / 10, 9) AS b, COUNT(*) AS n" + FROM + w.sql()
                + " AND r.id IS NOT NULL GROUP BY b", rs -> {
            counts[rs.getInt("b")] = rs.getLong("n");
        }, w.params().toArray());
        List<Bucket> out = new ArrayList<>();
        for (int b = 0; b < 10; b++) {
            out.add(new Bucket(b * 10, b * 10 + 10, counts[b]));
        }
        return out;
    }

    // ================================================================== chưa kiểm tra

    /**
     * Người chưa có điểm. Ngoài phạm vi theo đơn vị lúc vào lớp, danh sách CÁ NHÂN còn yêu cầu đơn vị HIỆN TẠI của
     * người đó trong phạm vi người xem (quyền xem chi tiết cá nhân, R2, C5): Admin không thấy họ tên người đã chuyển
     * ra khỏi nhánh, dù số liệu tổng hợp ở các thống kê khác vẫn tính họ cho đơn vị cũ.
     *
     * @param state ALL, ABSENT, NOT_TESTED
     */
    private static Where notTestedWhere(Filter f, Scope scope, String state) {
        Where w = where(f, scope);
        StringBuilder sb = new StringBuilder(w.sql()).append(" AND r.id IS NULL");
        List<Object> params = new ArrayList<>(w.params());
        SqlCondition current = scope.unitFilter("cu.code");
        sb.append(" AND ").append(current.sql());
        params.addAll(current.params());
        if ("ABSENT".equals(state)) {
            sb.append(" AND cp.absence_reason_id IS NOT NULL");
        } else if ("NOT_TESTED".equals(state)) {
            sb.append(" AND cp.absence_reason_id IS NULL");
        }
        return new Where(sb.toString(), params);
    }

    private static final String NOT_TESTED_FROM = FROM + """
             JOIN unit cu ON cu.id = u.unit_id
            LEFT JOIN absence_reason ar ON ar.id = cp.absence_reason_id
            LEFT JOIN rank rk ON rk.id = u.rank_id
            """;

    public List<NotTestedItem> notTested(Filter f, Scope scope, String state, PageQuery page) {
        Where w = notTestedWhere(f, scope, state);
        List<Object> params = new ArrayList<>(w.params());
        params.add(page.size());
        params.add(page.offset());
        return jdbc.query("SELECT cp.id AS participant_id, u.id AS user_id, " + UserSql.fullName("u", "su") + """
                         AS full_name, rk.symbol AS rank_symbol, u.personal_code, su.name AS unit_name,
                        ec.id AS class_id, ec.name AS class_name, ep.name AS period_name,
                        ec.exam_date, ar.name AS reason_name, cp.absence_note
                        """ + NOT_TESTED_FROM + w.sql()
                        + " ORDER BY ec.exam_date DESC, ec.id DESC, su.code, u.first_name, u.last_name, cp.id LIMIT ? OFFSET ?",
                (rs, i) -> new NotTestedItem(rs.getLong("participant_id"), rs.getLong("user_id"), rs.getString("full_name"),
                        rs.getString("rank_symbol"), rs.getString("personal_code"), rs.getString("unit_name"),
                        rs.getLong("class_id"), rs.getString("class_name"),
                        rs.getString("period_name"), rs.getString("exam_date"),
                        rs.getString("reason_name") == null ? "NOT_TESTED" : "ABSENT", rs.getString("reason_name"),
                        rs.getString("absence_note")),
                params.toArray());
    }

    /** Tổng theo trạng thái (không theo bộ lọc trạng thái) và theo lý do, cùng điều kiện như danh sách. */
    public List<ReasonCount> notTestedReasons(Filter f, Scope scope) {
        Where w = notTestedWhere(f, scope, "ALL");
        return jdbc.query("SELECT COALESCE(ar.name, '') AS reason, COUNT(*) AS n" + NOT_TESTED_FROM + w.sql()
                        + " GROUP BY ar.id ORDER BY (ar.id IS NULL), COUNT(*) DESC",
                (rs, i) -> new ReasonCount(rs.getString("reason"), rs.getLong("n")), w.params().toArray());
    }

    public long notTestedCount(Filter f, Scope scope, String state) {
        Where w = notTestedWhere(f, scope, state);
        Long n = jdbc.queryForObject("SELECT COUNT(*)" + NOT_TESTED_FROM + w.sql(), Long.class, w.params().toArray());
        return n == null ? 0 : n;
    }
}
