package vn.trainingstats.participant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.trainingstats.common.JdbcValues;
import vn.trainingstats.common.VietnameseText;
import vn.trainingstats.participant.ParticipantDtos.Absence;
import vn.trainingstats.participant.ParticipantDtos.Candidate;
import vn.trainingstats.participant.ParticipantDtos.Participant;
import vn.trainingstats.participant.ParticipantDtos.Person;
import vn.trainingstats.participant.ParticipantDtos.Removed;
import vn.trainingstats.participant.ParticipantDtos.ResultRef;
import vn.trainingstats.participant.ParticipantDtos.UnitRef;
import vn.trainingstats.security.Scope;
import vn.trainingstats.security.SqlCondition;
import vn.trainingstats.user.UserSql;

/**
 * Truy vấn người học. Mọi truy vấn nhận {@link Scope} và luôn kèm điều kiện {@code class_id}. Phạm vi theo người
 * tính trên đơn vị HIỆN TẠI của người học (quyền xem chi tiết cá nhân, R2); thống kê mới dùng unit_id_snapshot.
 */
@Repository
public class ParticipantRepository {

    /** Dữ liệu thô để kiểm tra trước khi thao tác. */
    public record Row(long id, long classId, long userId, String kind, String unitCode, boolean removed,
                      boolean hasResult, Long absenceReasonId) {
    }

    private static final String SELECT = "SELECT cp.id, cp.class_id, cp.user_id, cp.added_via, cp.request_id, cp.created_at,"
            + " cp.absence_reason_id, cp.absence_note, cp.removed_at, cp.removed_reason,"
            + " u.kind, u.personal_code, u.category, " + UserSql.fullName("u", "un") + " AS full_name,"
            + " r.symbol AS rank_symbol, r.name AS rank_name, un.name AS unit_name,"
            + " su.id AS snap_id, su.code AS snap_code, su.name AS snap_name,"
            + " ar.name AS absence_name,"
            + " res.id AS result_id, res.score_x10, res.grade, res.version AS result_version, res.graded_at,"
            + " res.updated_at AS result_updated_at, " + UserSql.fullName("gb", "gbu") + " AS graded_by_name,"
            + " " + UserSql.fullName("rb", "rbu") + " AS removed_by_name"
            + """
             FROM class_participant cp
            JOIN app_user u ON u.id = cp.user_id
            LEFT JOIN unit un ON un.id = u.unit_id
            LEFT JOIN rank r ON r.id = u.rank_id
            JOIN unit su ON su.id = cp.unit_id_snapshot
            LEFT JOIN absence_reason ar ON ar.id = cp.absence_reason_id
            LEFT JOIN result res ON res.participant_id = cp.id
            LEFT JOIN app_user gb ON gb.id = res.graded_by
            LEFT JOIN unit gbu ON gbu.id = gb.unit_id
            LEFT JOIN app_user rb ON rb.id = cp.removed_by
            LEFT JOIN unit rbu ON rbu.id = rb.unit_id
            """;

    private final JdbcTemplate jdbc;

    public ParticipantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param state ACTIVE (chưa gỡ), REMOVED (đã gỡ), ALL
     */
    public List<Participant> list(long classId, Scope scope, String state) {
        SqlCondition c = scope.userFilter("cp.user_id", "un.code");
        String removed = switch (state) {
            case "REMOVED" -> " AND cp.removed_at IS NOT NULL";
            case "ALL" -> "";
            default -> " AND cp.removed_at IS NULL";
        };
        return jdbc.query(SELECT + " WHERE cp.class_id = ? AND " + c.sql() + removed
                        + " ORDER BY (u.kind = 'UNIT'), su.code, u.first_name, u.last_name, cp.id",
                (rs, i) -> map(rs), c.paramsAfter(classId));
    }

    public Optional<Participant> findView(long classId, long participantId, Scope scope) {
        SqlCondition c = scope.userFilter("cp.user_id", "un.code");
        return jdbc.query(SELECT + " WHERE cp.class_id = ? AND cp.id = ? AND " + c.sql(), (rs, i) -> map(rs),
                c.paramsAfter(classId, participantId)).stream().findFirst();
    }

    public Optional<Row> findRow(long classId, long participantId, Scope scope) {
        SqlCondition c = scope.userFilter("cp.user_id", "un.code");
        return jdbc.query("""
                SELECT cp.id, cp.class_id, cp.user_id, u.kind, un.code AS unit_code, cp.removed_at, cp.absence_reason_id,
                       EXISTS (SELECT 1 FROM result r WHERE r.participant_id = cp.id) AS has_result
                FROM class_participant cp
                JOIN app_user u ON u.id = cp.user_id
                LEFT JOIN unit un ON un.id = u.unit_id
                WHERE cp.class_id = ? AND cp.id = ? AND\s""" + c.sql(),
                (rs, i) -> new Row(rs.getLong("id"), rs.getLong("class_id"), rs.getLong("user_id"), rs.getString("kind"),
                        rs.getString("unit_code"), rs.getString("removed_at") != null, rs.getInt("has_result") == 1,
                        JdbcValues.nullableLong(rs, "absence_reason_id")),
                c.paramsAfter(classId, participantId)).stream().findFirst();
    }

    /** Dòng người học đang ở trong lớp (chưa gỡ) của một người dùng, nếu có. */
    public Optional<Row> findActiveByUser(long classId, long userId) {
        return jdbc.query("""
                SELECT cp.id, cp.class_id, cp.user_id, u.kind, un.code AS unit_code, cp.removed_at, cp.absence_reason_id,
                       EXISTS (SELECT 1 FROM result r WHERE r.participant_id = cp.id) AS has_result
                FROM class_participant cp
                JOIN app_user u ON u.id = cp.user_id
                LEFT JOIN unit un ON un.id = u.unit_id
                WHERE cp.class_id = ? AND cp.user_id = ? AND cp.removed_at IS NULL
                """, (rs, i) -> new Row(rs.getLong("id"), rs.getLong("class_id"), rs.getLong("user_id"),
                rs.getString("kind"), rs.getString("unit_code"), false, rs.getInt("has_result") == 1,
                JdbcValues.nullableLong(rs, "absence_reason_id")), classId, userId).stream().findFirst();
    }

    /**
     * Tìm ứng viên trên TOÀN Lữ đoàn, ngoại lệ có chủ đích với quy tắc phạm vi (Q3, Q3b, R20): service chỉ cho
     * Super Admin và giáo viên của lớp đang mở gọi, và chỉ trả bốn trường trong {@link Candidate}.
     */
    public List<Candidate> candidates(long classId, String q, int limit) {
        return jdbc.query("SELECT u.id, " + UserSql.fullName("u", "un") + """
                 AS full_name, r.symbol AS rank_symbol, r.name AS rank_name, un.name AS unit_name, u.personal_code,
                       EXISTS (SELECT 1 FROM class_participant cp WHERE cp.class_id = ? AND cp.user_id = u.id
                               AND cp.removed_at IS NULL) AS in_class
                FROM app_user u
                JOIN unit un ON un.id = u.unit_id
                LEFT JOIN rank r ON r.id = u.rank_id
                WHERE u.kind = 'PERSON' AND u.status = 'ACTIVE' AND u.search_text LIKE ? ESCAPE '\\'
                ORDER BY u.first_name, u.last_name, u.id
                LIMIT ?
                """, (rs, i) -> new Candidate(rs.getLong("id"), rs.getString("full_name"), rs.getString("rank_symbol"),
                rs.getString("rank_name"), rs.getString("unit_name"), rs.getString("personal_code"),
                rs.getInt("in_class") == 1), classId, VietnameseText.likeContains(q), limit);
    }

    /** Id các đơn vị đang có bản ghi đại diện trong lớp (chưa gỡ). */
    public List<Long> unitIdsInClass(long classId) {
        return jdbc.queryForList("""
                SELECT u.unit_id FROM class_participant cp JOIN app_user u ON u.id = cp.user_id
                WHERE cp.class_id = ? AND cp.removed_at IS NULL AND u.kind = 'UNIT'
                """, Long.class, classId);
    }

    private static Participant map(ResultSet rs) throws SQLException {
        Long absenceId = JdbcValues.nullableLong(rs, "absence_reason_id");
        Long resultId = JdbcValues.nullableLong(rs, "result_id");
        String removedAt = rs.getString("removed_at");
        String state = resultId != null ? "TESTED" : absenceId != null ? "ABSENT" : "NOT_TESTED";
        return new Participant(rs.getLong("id"),
                new Person(rs.getLong("user_id"), rs.getString("kind"), rs.getString("full_name"),
                        rs.getString("personal_code"), rs.getString("rank_symbol"), rs.getString("rank_name"),
                        rs.getString("unit_name"), rs.getString("category")),
                new UnitRef(rs.getLong("snap_id"), rs.getString("snap_code"), rs.getString("snap_name")),
                state,
                absenceId == null ? null : new Absence(absenceId, rs.getString("absence_name"), rs.getString("absence_note")),
                resultId == null ? null : new ResultRef(resultId, rs.getInt("score_x10"), rs.getString("grade"),
                        rs.getInt("result_version"), rs.getString("graded_at"), rs.getString("graded_by_name"),
                        rs.getString("result_updated_at") != null),
                rs.getString("added_via"), JdbcValues.nullableLong(rs, "request_id"), rs.getString("created_at"),
                removedAt == null ? null : new Removed(removedAt, rs.getString("removed_by_name"), rs.getString("removed_reason")));
    }
}
