package vn.trainingstats.participant;

import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.trainingstats.audit.AuditAction;
import vn.trainingstats.audit.AuditService;
import vn.trainingstats.catalog.CatalogService;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.Times;
import vn.trainingstats.common.Validation;
import vn.trainingstats.common.VietnameseText;
import vn.trainingstats.exam.ClassRepository.ClassRow;
import vn.trainingstats.exam.ClassService;
import vn.trainingstats.participant.ParticipantDtos.AbsenceRequest;
import vn.trainingstats.participant.ParticipantDtos.AddRequest;
import vn.trainingstats.participant.ParticipantDtos.Participant;
import vn.trainingstats.participant.ParticipantDtos.UnitCandidate;
import vn.trainingstats.participant.ParticipantRepository.Row;
import vn.trainingstats.security.Scope;
import vn.trainingstats.security.ScopeService;
import vn.trainingstats.security.UserPrincipal;
import vn.trainingstats.unit.UnitService;
import vn.trainingstats.user.UnitRecordService;
import vn.trainingstats.user.UserRepository;

/**
 * Người học trong lớp (ARCHITECTURE 3.5, ma trận mục 5.2).
 * <ul>
 *   <li>Xem: Super Admin và giáo viên thấy cả lớp; Admin chỉ người trong nhánh mình; người học chỉ chính mình.</li>
 *   <li>Thêm trực tiếp, gỡ, ghi lý do vắng: Super Admin và giáo viên của lớp. Admin dùng đề nghị.</li>
 *   <li>Lớp đóng không nhận thêm người. Người đã có điểm chỉ Super Admin gỡ được, bắt buộc lý do, điểm giữ lại (Q9).</li>
 *   <li>Không vừa có điểm vừa có lý do vắng.</li>
 * </ul>
 */
@Service
public class ParticipantService {

    private static final int CANDIDATE_LIMIT = 20;
    private static final int MIN_QUERY = 2;

    /** Đối tượng đưa vào lớp đã được kiểm tra: một người (PERSON) hoặc bản ghi đại diện đơn vị (UNIT). */
    public record Target(long userId, long unitId, String unitCode, String kind) {
    }

    private final ParticipantRepository participants;
    private final ClassService classes;
    private final UserRepository users;
    private final UnitService units;
    private final UnitRecordService unitRecords;
    private final CatalogService catalogs;
    private final ScopeService scopes;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ParticipantService(ParticipantRepository participants, ClassService classes, UserRepository users,
                              UnitService units, UnitRecordService unitRecords, CatalogService catalogs,
                              ScopeService scopes, AuditService audit, JdbcTemplate jdbc, Clock clock) {
        this.participants = participants;
        this.classes = classes;
        this.users = users;
        this.units = units;
        this.unitRecords = unitRecords;
        this.catalogs = catalogs;
        this.scopes = scopes;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ================================================================== đọc

    public List<Participant> list(long classId, String state) {
        UserPrincipal me = scopes.currentPrincipal();
        classes.requireVisible(me, classId);
        String s = state == null || state.isBlank() ? "ACTIVE" : state.trim();
        if (!Set.of("ACTIVE", "REMOVED", "ALL").contains(s)) {
            throw ApiException.invalidField("state", "Bộ lọc phải là ACTIVE, REMOVED hoặc ALL.");
        }
        if (!"ACTIVE".equals(s) && !me.isSuperAdmin()) {
            throw ApiException.forbidden("FORBIDDEN", "Chỉ Super Admin xem được người đã gỡ khỏi nội dung kiểm tra.");
        }
        return participants.list(classId, classes.participantScope(me, classId), s);
    }

    /** Tìm người (mặc định) hoặc đơn vị ({@code kind = UNIT}) trên toàn Lữ đoàn để thêm vào lớp. */
    public List<?> candidates(long classId, String q, String kind) {
        UserPrincipal me = scopes.currentPrincipal();
        ClassRow row = classes.requireManageable(me, classId);
        requireOpen(row);
        String key = VietnameseText.searchKey(q);
        if (key.length() < MIN_QUERY) {
            throw ApiException.invalidField("q", "Nhập ít nhất " + MIN_QUERY + " ký tự để tìm.");
        }
        if ("UNIT".equals(kind)) {
            Set<Long> inClass = Set.copyOf(participants.unitIdsInClass(classId));
            List<UnitService.UnitView> tree = units.tree();
            Map<Long, UnitService.UnitView> byId = new LinkedHashMap<>();
            tree.forEach(u -> byId.put(u.id(), u));
            return tree.stream()
                    .filter(UnitService.UnitView::active)
                    .filter(u -> VietnameseText.searchKey(u.name(), u.code()).contains(key))
                    .sorted(Comparator.comparing(UnitService.UnitView::code))
                    .limit(CANDIDATE_LIMIT)
                    .map(u -> {
                        UnitService.UnitView parent = u.parentId() == null ? null : byId.get(u.parentId());
                        String path = parent == null ? u.name() : parent.name() + " / " + u.name();
                        return new UnitCandidate(u.id(), u.code(), u.name(), path, inClass.contains(u.id()));
                    })
                    .toList();
        }
        if (kind != null && !kind.isBlank() && !"PERSON".equals(kind)) {
            throw ApiException.invalidField("kind", "Loại phải là PERSON hoặc UNIT.");
        }
        return participants.candidates(classId, q, CANDIDATE_LIMIT);
    }

    // ================================================================== ghi

    @Transactional
    public Participant add(long classId, AddRequest req) {
        UserPrincipal me = scopes.currentPrincipal();
        ClassRow row = classes.requireManageable(me, classId);
        requireOpen(row);
        Target target = resolveTarget(req.userId(), req.unitId(), null, me);
        long pid = insert(row, target, "DIRECT", null, me);
        return participants.findView(classId, pid, Scope.all(me.userId())).orElseThrow();
    }

    @Transactional
    public Participant remove(long classId, long participantId, String reason) {
        UserPrincipal me = scopes.currentPrincipal();
        classes.requireManageable(me, classId);
        Row p = requireRow(classId, participantId, me);
        if (p.removed()) {
            throw ApiException.conflict("ALREADY_REMOVED", "Người này đã được gỡ khỏi nội dung kiểm tra.");
        }
        removeRow(p, reason, me);
        return participants.findView(classId, participantId, Scope.all(me.userId())).orElseThrow();
    }

    @Transactional
    public Participant setAbsence(long classId, long participantId, AbsenceRequest req) {
        UserPrincipal me = scopes.currentPrincipal();
        classes.requireManageable(me, classId);
        Row p = requireRow(classId, participantId, me);
        if (p.removed()) {
            throw ApiException.conflict("ALREADY_REMOVED", "Người này đã được gỡ khỏi nội dung kiểm tra.");
        }
        Long reasonId = req.reasonId();
        String note = null;
        if (reasonId != null) {
            if (p.hasResult()) {
                throw ApiException.conflict("HAS_RESULT", "Người này đã có điểm, không ghi lý do không kiểm tra được.");
            }
            Validation v = new Validation();
            note = v.optional("note", req.note(), 500);
            boolean keep = reasonId.equals(p.absenceReasonId());
            if (!keep && !catalogs.isActive(CatalogService.Type.ABSENCE_REASONS, reasonId)) {
                v.add("reasonId", "Lý do không hợp lệ hoặc đã bị khóa.");
            }
            v.throwIfAny();
        }
        jdbc.update("""
                UPDATE class_participant SET absence_reason_id = ?, absence_note = ?, updated_at = ?, updated_by = ?
                WHERE id = ?
                """, reasonId, note, Times.now(clock), me.userId(), participantId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("absenceReasonId", reasonId);
        after.put("note", note);
        audit.record(AuditAction.PARTICIPANT_ABSENCE_SET, "class_participant", participantId, p.unitCode(),
                p.absenceReasonId() == null ? null : Map.of("absenceReasonId", p.absenceReasonId()), after);
        return participants.findView(classId, participantId, Scope.all(me.userId())).orElseThrow();
    }

    // ================================================================== dùng chung với đề nghị (trong giao dịch ghi)

    /**
     * Kiểm đối tượng: người ({@code userId}) phải là PERSON đang hoạt động và thuộc một đơn vị; đơn vị ({@code unitId})
     * phải đang hoạt động, và được đổi thành bản ghi đại diện (tạo nếu chưa có). {@code limit} khác null thì đối tượng
     * phải nằm trong phạm vi đó (Admin đề nghị), nếu không trả 404 như thể không tồn tại.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Target resolveTarget(Long userId, Long unitId, Scope limit, UserPrincipal me) {
        if ((userId == null) == (unitId == null)) {
            throw ApiException.badRequest("TARGET_REQUIRED", "Chọn đúng một người hoặc một đơn vị.");
        }
        if (unitId != null) {
            UnitService.UnitView unit = units.find(unitId)
                    .filter(u -> limit == null || limit.coversUnit(u.code()))
                    .orElseThrow(() -> ApiException.notFound("Không tìm thấy đơn vị."));
            if (!unit.active()) {
                throw ApiException.invalidField("unitId", "Đơn vị đã bị khóa.");
            }
            return new Target(unitRecords.findOrCreate(unit, me.userId()), unit.id(), unit.code(), "UNIT");
        }
        UserRepository.Target t = users.findTarget(userId, limit == null ? Scope.all(me.userId()) : limit)
                .orElseThrow(() -> ApiException.notFound("Không tìm thấy người dùng."));
        if (!"PERSON".equals(t.kind())) {
            // Bản ghi đại diện đơn vị đi qua unitId, để luôn kiểm trạng thái của chính đơn vị.
            throw ApiException.invalidField("userId", "Chọn đơn vị qua trường unitId.");
        }
        if (!"ACTIVE".equals(t.status())) {
            throw ApiException.invalidField("userId", "Người này đang bị khóa hoặc đã bị xóa.");
        }
        if (t.unitId() == null) {
            throw ApiException.invalidField("userId", "Super Admin không thuộc đơn vị nào nên không vào nội dung kiểm tra được.");
        }
        return new Target(t.id(), t.unitId(), t.unitCode(), "PERSON");
    }

    /** Thêm vào lớp, lưu đơn vị tại thời điểm vào lớp (unit_id_snapshot, Q4). Trùng người trong lớp trả 409. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long insert(ClassRow row, Target target, String via, Long requestId, UserPrincipal me) {
        String now = Times.now(clock);
        try {
            jdbc.update("""
                    INSERT INTO class_participant (class_id, user_id, unit_id_snapshot, added_via, request_id,
                                                   created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, row.id(), target.userId(), target.unitId(), via, requestId, now, me.userId(), now, me.userId());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("ALREADY_IN_CLASS", "UNIT".equals(target.kind())
                    ? "Đơn vị này đã có trong nội dung kiểm tra." : "Người này đã có trong nội dung kiểm tra.");
        }
        long pid = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("classId", row.id());
        after.put("className", row.name());
        after.put("userId", target.userId());
        after.put("kind", target.kind());
        after.put("unitCodeSnapshot", target.unitCode());
        after.put("addedVia", via);
        after.put("requestId", requestId);
        audit.record(AuditAction.PARTICIPANT_ADDED, "class_participant", pid, target.unitCode(), null, after);
        return pid;
    }

    /** Gỡ mềm. Người đã có điểm: chỉ Super Admin, bắt buộc lý do, điểm giữ nguyên trong DB (Q9). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void removeRow(Row p, String reason, UserPrincipal me) {
        Validation v = new Validation();
        String r = v.optional("reason", reason, 500);
        v.throwIfAny();
        if (p.hasResult()) {
            if (!me.isSuperAdmin()) {
                throw ApiException.forbidden("HAS_RESULT", "Người này đã có điểm. Chỉ Super Admin gỡ được.");
            }
            if (r == null) {
                throw ApiException.invalidField("reason", "Người này đã có điểm, phải nhập lý do gỡ.");
            }
        }
        String now = Times.now(clock);
        jdbc.update("""
                UPDATE class_participant SET removed_at = ?, removed_by = ?, removed_reason = ?, updated_at = ?, updated_by = ?
                WHERE id = ? AND removed_at IS NULL
                """, now, me.userId(), r, now, me.userId(), p.id());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("classId", p.classId());
        after.put("userId", p.userId());
        after.put("hadResult", p.hasResult());
        after.put("reason", r);
        audit.record(AuditAction.PARTICIPANT_REMOVED, "class_participant", p.id(), p.unitCode(), null, after);
    }

    // ================================================================== hỗ trợ

    private Row requireRow(long classId, long participantId, UserPrincipal me) {
        return participants.findRow(classId, participantId, Scope.all(me.userId()))
                .orElseThrow(() -> ApiException.notFound("Không tìm thấy người học trong nội dung kiểm tra."));
    }

    public static void requireOpen(ClassRow row) {
        if (!row.open()) {
            throw ApiException.conflict("CLASS_CLOSED", "Nội dung kiểm tra đã đóng, không thêm người hoặc đề nghị mới được.");
        }
    }
}
