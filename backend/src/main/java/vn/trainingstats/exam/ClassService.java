package vn.trainingstats.exam;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.trainingstats.audit.AuditAction;
import vn.trainingstats.audit.AuditService;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.OptimisticLock;
import vn.trainingstats.common.PageQuery;
import vn.trainingstats.common.PageResult;
import vn.trainingstats.common.Times;
import vn.trainingstats.common.Validation;
import vn.trainingstats.exam.ClassDtos.Access;
import vn.trainingstats.exam.ClassDtos.ClassListItem;
import vn.trainingstats.exam.ClassDtos.ClassView;
import vn.trainingstats.exam.ClassDtos.OpenClass;
import vn.trainingstats.exam.ClassDtos.SaveRequest;
import vn.trainingstats.exam.ClassRepository.ClassRow;
import vn.trainingstats.exam.ClassRepository.Detail;
import vn.trainingstats.security.ClassAccess;
import vn.trainingstats.security.Scope;
import vn.trainingstats.security.ScopeService;
import vn.trainingstats.security.UserPrincipal;

/**
 * Lớp kiểm tra. Chỉ Super Admin tạo, sửa, đóng, mở, xóa mềm lớp và gán giáo viên.
 * <ul>
 *   <li>Lớp đóng không nhận thêm người học và không nhận đề nghị mới (kiểm ở ParticipantService, RequestService).</li>
 *   <li>Lớp không thấy được thì trả 404 như thể không tồn tại.</li>
 *   <li>Admin thấy mọi lớp (kể cả lớp chưa có ai thuộc nhánh mình), nhưng danh sách người học, số người, thống kê
 *       của lớp chỉ gồm người trong nhánh (lọc ở ParticipantService, StatsService).</li>
 *   <li>Không xóa được lớp đã có điểm. Xóa lớp thì các đề nghị đang chờ của lớp chuyển sang Đã hủy.</li>
 * </ul>
 */
@Service
public class ClassService {

    private static final int MAX_TEACHERS = 20;

    private final ClassRepository classes;
    private final PeriodService periods;
    private final ClassAccess classAccess;
    private final ScopeService scopes;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ClassService(ClassRepository classes, PeriodService periods, ClassAccess classAccess,
                        ScopeService scopes, AuditService audit, JdbcTemplate jdbc, Clock clock) {
        this.classes = classes;
        this.periods = periods;
        this.classAccess = classAccess;
        this.scopes = scopes;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ================================================================== đọc

    public PageResult<ClassListItem> search(Long periodId, String status, boolean teachingOnly, boolean ungradedOnly,
                                            PageQuery page) {
        if (status != null && !Set.of("OPEN", "CLOSED").contains(status)) {
            throw ApiException.invalidField("status", "Trạng thái phải là OPEN hoặc CLOSED.");
        }
        UserPrincipal me = scopes.currentPrincipal();
        return classes.search(me, scopes.of(me),
                new ClassRepository.Filter(periodId, status, teachingOnly, ungradedOnly), page);
    }

    public List<OpenClass> openClasses() {
        return classes.openClasses();
    }

    public ClassView get(long id) {
        UserPrincipal me = scopes.currentPrincipal();
        requireVisible(me, id);
        Detail d = classes.findDetail(id).orElseThrow(ClassService::notFound);
        boolean manage = classAccess.canManage(me, id);
        String status = d.effectiveStatus();
        Access access = new Access(manage, me.isAdmin() && "OPEN".equals(status), me.isSuperAdmin());
        int count = manage ? classes.countActiveParticipants(id) : classes.countParticipantsVisible(id, scopes.of(me));
        return new ClassView(d.id(), d.name(), d.period(), d.examDate(), d.location(), d.note(),
                status, d.version(), classes.teachers(id), count, access);
    }

    /** Lớp người gọi được xem. Không có, đã xóa, hoặc không liên quan đều trả 404. */
    public ClassRow requireVisible(UserPrincipal me, long id) {
        ClassRow row = classes.findRow(id).orElseThrow(ClassService::notFound);
        if (me.isSuperAdmin() || me.isAdmin() || classAccess.isTeacher(me.userId(), id)
                || classes.isParticipant(id, me.userId())) {
            return row;
        }
        throw notFound();
    }

    /** Lớp người gọi được quản lý (Super Admin hoặc giáo viên của lớp). Ngoài ra trả 404. */
    public ClassRow requireManageable(UserPrincipal me, long id) {
        ClassRow row = classes.findRow(id).orElseThrow(ClassService::notFound);
        classAccess.requireManage(me, id);
        return row;
    }

    // ================================================================== ghi (Super Admin)

    @Transactional
    public ClassView create(SaveRequest req) {
        Fields f = validate(req, null);
        String now = Times.now(clock);
        long actor = scopes.currentPrincipal().userId();
        jdbc.update("""
                INSERT INTO exam_class (period_id, name, exam_date, location, note,
                                        created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, f.periodId, f.name, f.examDate, f.location, f.note, now, actor, now, actor);
        long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        audit.record(AuditAction.CLASS_CREATED, "exam_class", id, null, null, classes.findDetail(id).orElseThrow());
        if (req.teacherIds() != null) {
            assignTeachers(id, req.teacherIds(), "teacherIds");
        }
        return get(id);
    }

    @Transactional
    public ClassView update(long id, SaveRequest req) {
        ClassRow row = classes.findRow(id).orElseThrow(ClassService::notFound);
        Fields f = validate(req, row);
        if (req.version() == null) {
            throw ApiException.invalidField("version", "Thiếu phiên bản dữ liệu, hãy tải lại trang.");
        }
        Detail before = classes.findDetail(id).orElseThrow();
        int n = jdbc.update("""
                UPDATE exam_class SET period_id = ?, name = ?, exam_date = ?, location = ?, note = ?,
                       updated_at = ?, updated_by = ?, version = version + 1
                WHERE id = ? AND version = ? AND deleted_at IS NULL
                """, f.periodId, f.name, f.examDate, f.location, f.note, Times.now(clock),
                scopes.currentPrincipal().userId(), id, req.version());
        OptimisticLock.check(n);
        audit.record(AuditAction.CLASS_UPDATED, "exam_class", id, null, before, classes.findDetail(id).orElseThrow());
        if (req.teacherIds() != null) {
            assignTeachers(id, req.teacherIds(), "teacherIds");
        }
        return get(id);
    }

    @Transactional
    public ClassView setStatus(long id, String status) {
        ClassRow row = classes.findRow(id).orElseThrow(ClassService::notFound);
        if (!"OPEN".equals(status) && !"CLOSED".equals(status)) {
            throw ApiException.invalidField("status", "Trạng thái phải là OPEN hoặc CLOSED.");
        }
        if ("OPEN".equals(status) && row.periodClosed()) {
            throw ApiException.conflict("PERIOD_CLOSED",
                    "Đợt kiểm tra đã khóa nên mọi nội dung của đợt đều đóng. Mở lại đợt trước khi mở nội dung này.");
        }
        if (!status.equals(row.status())) {
            jdbc.update("UPDATE exam_class SET status = ?, updated_at = ?, updated_by = ?, version = version + 1 WHERE id = ?",
                    status, Times.now(clock), scopes.currentPrincipal().userId(), id);
            audit.record(AuditAction.CLASS_STATUS_CHANGED, "exam_class", id, null,
                    Map.of("status", row.status()), Map.of("status", status));
        }
        return get(id);
    }

    @Transactional
    public void delete(long id) {
        classes.findRow(id).orElseThrow(ClassService::notFound);
        int results = classes.countResults(id);
        if (results > 0) {
            throw ApiException.conflict("CLASS_HAS_RESULTS",
                    "Nội dung kiểm tra đã có " + results + " điểm, không xóa được. Có thể đóng nội dung kiểm tra thay vì xóa.");
        }
        Detail before = classes.findDetail(id).orElseThrow();
        String now = Times.now(clock);
        long actor = scopes.currentPrincipal().userId();
        jdbc.update("""
                UPDATE exam_class SET deleted_at = ?, deleted_by = ?, updated_at = ?, updated_by = ?, version = version + 1
                WHERE id = ?
                """, now, actor, now, actor, id);
        int cancelled = jdbc.update("""
                UPDATE class_request SET status = 'CANCELLED', decided_by = ?, decided_at = ?, decision_note = ?
                WHERE class_id = ? AND status = 'PENDING'
                """, actor, now, "Nội dung kiểm tra đã bị xóa.", id);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("deleted", true);
        after.put("cancelledRequests", cancelled);
        audit.record(AuditAction.CLASS_DELETED, "exam_class", id, null, before, after);
    }

    /** Thay toàn bộ danh sách giáo viên. Chỉ người có tài khoản, đang hoạt động. Super Admin không cần gán. */
    @Transactional
    public ClassView setTeachers(long id, List<Long> userIds) {
        classes.findRow(id).orElseThrow(ClassService::notFound);
        assignTeachers(id, userIds, "userIds");
        return get(id);
    }

    /** Gọi trong giao dịch ghi. Lỗi gắn vào trường {@code field} (userIds khi gán riêng, teacherIds khi lưu cùng form). */
    private void assignTeachers(long id, List<Long> userIds, String field) {
        List<Long> ids = userIds == null ? List.of() : userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > MAX_TEACHERS) {
            throw ApiException.invalidField(field, "Tối đa " + MAX_TEACHERS + " giám khảo mỗi nội dung kiểm tra.");
        }
        List<String> invalid = new ArrayList<>();
        for (Long uid : ids) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT kind, status, username, password_hash IS NOT NULL AS has_password FROM app_user WHERE id = ?
                    """, uid);
            if (rows.isEmpty()) {
                invalid.add("#" + uid + " không tồn tại");
                continue;
            }
            Map<String, Object> r = rows.get(0);
            boolean ok = "PERSON".equals(r.get("kind")) && "ACTIVE".equals(r.get("status")) && r.get("username") != null
                    && ((Number) r.get("has_password")).intValue() == 1;
            if (!ok) {
                invalid.add("#" + uid);
            }
        }
        if (!invalid.isEmpty()) {
            throw ApiException.invalidField(field,
                    "Giám khảo phải là người có tài khoản đăng nhập và đang hoạt động (" + String.join(", ", invalid) + ").");
        }
        List<Long> before = classes.teacherIds(id);
        // class_teacher là bảng gán: gán lại là thay tập hợp dòng (ARCHITECTURE 2.8 S4).
        jdbc.update("DELETE FROM class_teacher WHERE class_id = ?", id);
        String now = Times.now(clock);
        long actor = scopes.currentPrincipal().userId();
        for (Long uid : ids) {
            jdbc.update("INSERT INTO class_teacher (class_id, user_id, created_at, created_by) VALUES (?, ?, ?, ?)",
                    id, uid, now, actor);
        }
        audit.record(AuditAction.CLASS_TEACHERS_CHANGED, "exam_class", id, null,
                Map.of("teacherIds", before), Map.of("teacherIds", classes.teacherIds(id)));
    }

    // ================================================================== hỗ trợ

    private record Fields(long periodId, String name, String examDate, String location, String note) {
    }

    /** {@code current} null khi tạo mới. Đợt đóng không nhận lớp mới (đổi sang đợt đóng cũng không được). */
    private Fields validate(SaveRequest req, ClassRow current) {
        Validation v = new Validation();
        String name = v.required("name", req.name(), 150, "Nhập tên nội dung.");
        String examDate = v.date("examDate", req.examDate());
        String location = v.optional("location", req.location(), 200);
        String note = v.optional("note", req.note(), 500);
        if (req.periodId() == null) {
            v.add("periodId", "Chọn đợt kiểm tra.");
        } else {
            PeriodService.PeriodView p = periods.find(req.periodId()).orElse(null);
            boolean samePeriod = current != null && current.periodId() == req.periodId();
            if (p == null) {
                v.add("periodId", "Không tìm thấy đợt kiểm tra.");
            } else if (!"OPEN".equals(p.status()) && !samePeriod) {
                v.add("periodId", "Đợt kiểm tra đã đóng, không thêm nội dung kiểm tra vào được.");
            }
        }
        v.throwIfAny();
        return new Fields(req.periodId(), name, examDate, location, note);
    }

    private static ApiException notFound() {
        return ApiException.notFound("Không tìm thấy nội dung kiểm tra.");
    }

    /** Phạm vi để lọc người học: người quản lý lớp thấy cả lớp, người khác theo phạm vi của mình. */
    public Scope participantScope(UserPrincipal me, long classId) {
        // Quyền giáo viên là quyền trên cả lớp (không giới hạn nhánh), nên dùng phạm vi toàn bộ nhưng truy vấn luôn
        // kèm điều kiện class_id.
        return classAccess.canManage(me, classId) ? Scope.all(me.userId()) : scopes.of(me);
    }
}
