package vn.trainingstats.result;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.trainingstats.common.JdbcValues;
import vn.trainingstats.exam.ClassStatusSql;
import vn.trainingstats.participant.ParticipantDtos.Absence;
import vn.trainingstats.result.ResultDtos.ClassInfo;
import vn.trainingstats.result.ResultDtos.ClassResult;
import vn.trainingstats.result.ResultDtos.HistoryEntry;
import vn.trainingstats.result.ResultDtos.Score;
import vn.trainingstats.security.Scope;
import vn.trainingstats.security.SqlCondition;
import vn.trainingstats.user.UserSql;

/**
 * Truy vấn điểm. Mọi truy vấn nhận {@link Scope}, ghép trên đơn vị HIỆN TẠI của người học (quyền xem chi tiết cá
 * nhân, R2), như ParticipantRepository.
 */
@Repository
public class ResultRepository {

    /** Điểm kèm người học và lớp, để kiểm tra trước khi sửa. */
    public record ResultRow(long id, long participantId, long classId, long userId, String unitCode, int scoreX10,
                            String grade, int version, boolean removed) {
    }

    private final JdbcTemplate jdbc;

    public ResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ResultRow> findRow(long resultId, Scope scope) {
        SqlCondition c = scope.userFilter("cp.user_id", "un.code");
        return jdbc.query("""
                SELECT r.id, r.participant_id, cp.class_id, cp.user_id, un.code AS unit_code, r.score_x10, r.grade,
                       r.version, cp.removed_at
                FROM result r
                JOIN class_participant cp ON cp.id = r.participant_id
                JOIN exam_class ec ON ec.id = cp.class_id AND ec.deleted_at IS NULL
                JOIN app_user u ON u.id = cp.user_id
                LEFT JOIN unit un ON un.id = u.unit_id
                WHERE r.id = ? AND\s""" + c.sql(),
                (rs, i) -> new ResultRow(rs.getLong("id"), rs.getLong("participant_id"), rs.getLong("class_id"),
                        rs.getLong("user_id"), rs.getString("unit_code"), rs.getInt("score_x10"), rs.getString("grade"),
                        rs.getInt("version"), rs.getString("removed_at") != null),
                c.paramsAfter(resultId)).stream().findFirst();
    }

    /** Các lần sửa của một điểm, mới nhất trước. */
    public List<HistoryEntry> history(long resultId, Scope scope) {
        SqlCondition c = scope.userFilter("cp.user_id", "un.code");
        return jdbc.query("SELECT h.id, h.old_score_x10, h.new_score_x10, h.reason, h.changed_at, "
                        + UserSql.fullName("cb", "cbu") + """
                         AS changed_by_name
                        FROM result_history h
                        JOIN result r ON r.id = h.result_id
                        JOIN class_participant cp ON cp.id = r.participant_id
                        JOIN app_user u ON u.id = cp.user_id
                        LEFT JOIN unit un ON un.id = u.unit_id
                        JOIN app_user cb ON cb.id = h.changed_by
                        LEFT JOIN unit cbu ON cbu.id = cb.unit_id
                        WHERE h.result_id = ? AND\s""" + c.sql() + " ORDER BY h.changed_at DESC, h.id DESC",
                (rs, i) -> new HistoryEntry(rs.getLong("id"), rs.getInt("old_score_x10"), rs.getInt("new_score_x10"),
                        rs.getString("reason"), rs.getString("changed_by_name"), rs.getString("changed_at")),
                c.paramsAfter(resultId));
    }

    /**
     * Các lớp một người đã tham gia (chưa bị gỡ, lớp chưa xóa) và điểm, lớp mới nhất trước. Người đã bị gỡ khỏi lớp
     * không tính vào lịch sử (Q9), dù điểm vẫn còn trong DB.
     */
    public List<ClassResult> participations(long userId, Scope scope) {
        SqlCondition c = scope.userFilter("cp.user_id", "un.code");
        return jdbc.query("""
                SELECT cp.id AS participant_id, ec.id AS class_id, ec.name AS class_name, ep.name AS period_name,
                       ec.exam_date, """ + ClassStatusSql.effective("ec", "ep") + """
                 AS class_status,
                       cp.absence_reason_id, ar.name AS absence_name, cp.absence_note,
                       r.id AS result_id, r.score_x10, r.grade, r.graded_at, r.updated_at AS result_updated_at
                FROM class_participant cp
                JOIN exam_class ec ON ec.id = cp.class_id AND ec.deleted_at IS NULL
                JOIN exam_period ep ON ep.id = ec.period_id
                JOIN app_user u ON u.id = cp.user_id
                LEFT JOIN unit un ON un.id = u.unit_id
                LEFT JOIN absence_reason ar ON ar.id = cp.absence_reason_id
                LEFT JOIN result r ON r.participant_id = cp.id
                WHERE cp.user_id = ? AND cp.removed_at IS NULL AND\s""" + c.sql() + """
                 ORDER BY ec.exam_date DESC, ec.id DESC
                """, (rs, i) -> {
            Long absenceId = JdbcValues.nullableLong(rs, "absence_reason_id");
            Long resultId = JdbcValues.nullableLong(rs, "result_id");
            String state = resultId != null ? "TESTED" : absenceId != null ? "ABSENT" : "NOT_TESTED";
            return new ClassResult(rs.getLong("participant_id"),
                    new ClassInfo(rs.getLong("class_id"), rs.getString("class_name"), rs.getString("period_name"),
                            rs.getString("exam_date"), rs.getString("class_status")),
                    state,
                    absenceId == null ? null : new Absence(absenceId, rs.getString("absence_name"), rs.getString("absence_note")),
                    resultId == null ? null : new Score(rs.getInt("score_x10"), rs.getString("grade"),
                            rs.getString("graded_at"), rs.getString("result_updated_at") != null));
        }, c.paramsAfter(userId));
    }
}
