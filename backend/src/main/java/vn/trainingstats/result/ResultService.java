package vn.trainingstats.result;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.trainingstats.audit.AuditAction;
import vn.trainingstats.audit.AuditService;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.OptimisticLock;
import vn.trainingstats.common.Times;
import vn.trainingstats.common.Validation;
import vn.trainingstats.exam.ClassService;
import vn.trainingstats.participant.ParticipantDtos.Participant;
import vn.trainingstats.participant.ParticipantRepository;
import vn.trainingstats.participant.ParticipantRepository.Row;
import vn.trainingstats.result.ResultDtos.ClassResult;
import vn.trainingstats.result.ResultDtos.EditRequest;
import vn.trainingstats.result.ResultDtos.GradeRequest;
import vn.trainingstats.result.ResultDtos.HistoryEntry;
import vn.trainingstats.result.ResultRepository.ResultRow;
import vn.trainingstats.security.Scope;
import vn.trainingstats.security.ScopeService;
import vn.trainingstats.security.UserPrincipal;
import vn.trainingstats.user.UserRepository;

/**
 * Điểm (ARCHITECTURE 2.4, 3.5, ma trận 5.2).
 * <ul>
 *   <li>Nhập lần đầu: Super Admin và giáo viên của lớp. Mỗi người học một điểm (UNIQUE ở DB, kể cả khi hai người
 *       chấm lưu cùng lúc). Giáo viên không chấm cho chính mình (R16). Nhập điểm thì xóa lý do vắng cùng giao dịch.</li>
 *   <li>Lớp đã đóng vẫn nhập điểm được: đóng lớp chỉ chặn thêm người và đề nghị mới (C9), và thường điểm được nhập
 *       sau buổi kiểm tra. Người đã bị gỡ khỏi lớp thì không.</li>
 *   <li>Sửa điểm: chỉ Super Admin, bắt buộc lý do và phiên bản, ghi result_history và audit log.</li>
 *   <li>Hồ sơ điểm: chính mình, hoặc Admin trong nhánh, hoặc Super Admin. Không tính dòng đã bị gỡ khỏi lớp.</li>
 * </ul>
 */
@Service
public class ResultService {

    private static final int MAX_REASON = 500;

    private final ResultRepository results;
    private final ParticipantRepository participants;
    private final ClassService classes;
    private final UserRepository users;
    private final ScopeService scopes;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ResultService(ResultRepository results, ParticipantRepository participants, ClassService classes,
                         UserRepository users, ScopeService scopes, AuditService audit, JdbcTemplate jdbc, Clock clock) {
        this.results = results;
        this.participants = participants;
        this.classes = classes;
        this.users = users;
        this.scopes = scopes;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ================================================================== nhập, sửa

    @Transactional
    public Participant grade(long classId, long participantId, GradeRequest req) {
        UserPrincipal me = scopes.currentPrincipal();
        classes.requireManageable(me, classId);
        Validation v = new Validation();
        Integer score = Scores.toX10(req == null ? null : req.score(), v, "score");
        v.throwIfAny();
        Row p = participants.findRow(classId, participantId, Scope.all(me.userId()))
                .orElseThrow(() -> ApiException.notFound("Không tìm thấy người học trong nội dung kiểm tra."));
        if (p.removed()) {
            throw ApiException.conflict("ALREADY_REMOVED", "Người này đã được gỡ khỏi nội dung kiểm tra.");
        }
        if (p.userId() == me.userId()) {
            throw ApiException.forbidden("SELF_GRADING", "Không được tự chấm điểm cho chính mình.");
        }
        if (p.hasResult()) {
            throw alreadyGraded();
        }
        insertResult(classId, p, score, me, Times.now(clock), null);
        return participants.findView(classId, participantId, Scope.all(me.userId())).orElseThrow();
    }

    /**
     * Ghi điểm lần đầu cho một người học (đã kiểm quyền, chưa có điểm, không tự chấm). Xóa lý do vắng cùng giao dịch,
     * ghi audit. Dùng chung cho chấm từng người và nhập Excel ({@code source} = "IMPORT").
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long insertResult(long classId, Row p, int score, UserPrincipal me, String now, String source) {
        try {
            jdbc.update("INSERT INTO result (participant_id, score_x10, graded_by, graded_at) VALUES (?, ?, ?, ?)",
                    p.id(), score, me.userId(), now);
        } catch (DuplicateKeyException e) {
            throw alreadyGraded();
        }
        long resultId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        if (p.absenceReasonId() != null) {
            jdbc.update("""
                    UPDATE class_participant SET absence_reason_id = NULL, absence_note = NULL, updated_at = ?, updated_by = ?
                    WHERE id = ?
                    """, now, me.userId(), p.id());
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("classId", classId);
        after.put("participantId", p.id());
        after.put("userId", p.userId());
        after.put("scoreX10", score);
        after.put("grade", gradeOf(resultId));
        after.put("clearedAbsenceReasonId", p.absenceReasonId());
        if (source != null) {
            after.put("source", source);
        }
        audit.record(AuditAction.RESULT_CREATED, "result", resultId, p.unitCode(), null, after);
        return resultId;
    }

    @Transactional
    public Participant edit(long resultId, EditRequest req) {
        UserPrincipal me = requireSuperAdmin();
        ResultRow r = results.findRow(resultId, scopes.of(me)).orElseThrow(ResultService::notFound);
        Validation v = new Validation();
        Integer score = Scores.toX10(req.score(), v, "score");
        String reason = v.required("reason", req.reason(), MAX_REASON, "Nhập lý do sửa điểm.");
        if (score != null && score == r.scoreX10()) {
            v.add("score", "Điểm mới giống điểm hiện tại.");
        }
        v.throwIfAny();
        if (req.version() == null) {
            throw ApiException.invalidField("version", "Thiếu phiên bản dữ liệu, hãy tải lại trang.");
        }
        if (r.removed()) {
            throw ApiException.conflict("ALREADY_REMOVED", "Người này đã được gỡ khỏi nội dung kiểm tra, điểm không còn tính.");
        }
        String now = Times.now(clock);
        int n = jdbc.update("""
                UPDATE result SET score_x10 = ?, version = version + 1, updated_at = ?, updated_by = ?
                WHERE id = ? AND version = ?
                """, score, now, me.userId(), resultId, req.version());
        OptimisticLock.check(n);
        jdbc.update("""
                INSERT INTO result_history (result_id, old_score_x10, new_score_x10, reason, changed_by, changed_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, resultId, r.scoreX10(), score, reason, me.userId(), now);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("scoreX10", r.scoreX10());
        before.put("grade", r.grade());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("scoreX10", score);
        after.put("grade", gradeOf(resultId));
        after.put("reason", reason);
        after.put("classId", r.classId());
        after.put("participantId", r.participantId());
        audit.record(AuditAction.RESULT_UPDATED, "result", resultId, r.unitCode(), before, after);
        return participants.findView(r.classId(), r.participantId(), Scope.all(me.userId())).orElseThrow();
    }

    public List<HistoryEntry> history(long resultId) {
        UserPrincipal me = requireSuperAdmin();
        Scope scope = scopes.of(me);
        results.findRow(resultId, scope).orElseThrow(ResultService::notFound);
        return results.history(resultId, scope);
    }

    // ================================================================== hồ sơ

    public List<ClassResult> myClasses() {
        UserPrincipal me = scopes.currentPrincipal();
        return results.participations(me.userId(), Scope.self(me.userId()));
    }

    /** Lịch sử điểm của một người: chính mình, Admin trong nhánh (theo đơn vị hiện tại), Super Admin. */
    public List<ClassResult> userHistory(long userId) {
        Scope scope = scopes.current();
        UserRepository.Target t = users.findTarget(userId, scope)
                .orElseThrow(() -> ApiException.notFound("Không tìm thấy người dùng."));
        return results.participations(t.id(), scope);
    }

    // ================================================================== hỗ trợ

    private UserPrincipal requireSuperAdmin() {
        UserPrincipal me = scopes.currentPrincipal();
        if (!me.isSuperAdmin()) {
            throw ApiException.forbidden("FORBIDDEN", "Chỉ Super Admin sửa và xem lịch sử sửa điểm.");
        }
        return me;
    }

    /** Xếp loại là cột tính ở DB: đọc lại thay vì tự tính. */
    private String gradeOf(long resultId) {
        return jdbc.queryForObject("SELECT grade FROM result WHERE id = ?", String.class, resultId);
    }

    private static ApiException alreadyGraded() {
        return ApiException.conflict("ALREADY_GRADED", "Người này đã có điểm. Chỉ Super Admin sửa được điểm.");
    }

    private static ApiException notFound() {
        return ApiException.notFound("Không tìm thấy điểm.");
    }
}
