package vn.trainingstats.request;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.trainingstats.audit.AuditAction;
import vn.trainingstats.audit.AuditService;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.PageQuery;
import vn.trainingstats.common.PageResult;
import vn.trainingstats.common.Times;
import vn.trainingstats.common.Validation;
import vn.trainingstats.exam.ClassRepository;
import vn.trainingstats.exam.ClassRepository.ClassRow;
import vn.trainingstats.exam.ClassService;
import vn.trainingstats.notification.NotificationService;
import vn.trainingstats.participant.ParticipantRepository;
import vn.trainingstats.participant.ParticipantService;
import vn.trainingstats.participant.ParticipantService.Target;
import vn.trainingstats.request.RequestDtos.CreateRequest;
import vn.trainingstats.request.RequestDtos.RequestView;
import vn.trainingstats.request.RequestRepository.Row;
import vn.trainingstats.security.ClassAccess;
import vn.trainingstats.security.Scope;
import vn.trainingstats.security.ScopeService;
import vn.trainingstats.security.UserPrincipal;
import vn.trainingstats.user.UserRepository;

/**
 * Đề nghị thêm hoặc gỡ người học (ARCHITECTURE 3.5).
 * <ul>
 *   <li>Admin tạo đề nghị cho đối tượng trong nhánh mình, vào lớp đang mở. Ngoài nhánh thì 404.</li>
 *   <li>Giáo viên của lớp và Super Admin duyệt hoặc từ chối (kèm lý do). Người đề nghị tự hủy khi còn chờ.</li>
 *   <li>Đề nghị mới báo cho giáo viên của lớp và mọi Super Admin. Kết quả duyệt, từ chối báo cho người đề nghị.</li>
 *   <li>Duyệt thêm: đơn vị lúc vào lớp lấy theo đơn vị HIỆN TẠI của người đó, không theo lúc đề nghị.</li>
 *   <li>Duyệt gỡ người đã có điểm: chỉ Super Admin, và ghi chú duyệt chính là lý do gỡ (Q9).</li>
 * </ul>
 */
@Service
public class RequestService {

    private static final Set<String> TYPES = Set.of("ADD", "REMOVE");
    private static final Set<String> STATUSES = Set.of("PENDING", "APPROVED", "REJECTED", "CANCELLED");

    private final RequestRepository requests;
    private final ClassService classService;
    private final ClassRepository classRepo;
    private final ClassAccess classAccess;
    private final ParticipantService participantService;
    private final ParticipantRepository participantRepo;
    private final UserRepository users;
    private final NotificationService notifications;
    private final ScopeService scopes;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RequestService(RequestRepository requests, ClassService classService, ClassRepository classRepo,
                          ClassAccess classAccess, ParticipantService participantService,
                          ParticipantRepository participantRepo, UserRepository users,
                          NotificationService notifications, ScopeService scopes, AuditService audit, JdbcTemplate jdbc,
                          Clock clock) {
        this.requests = requests;
        this.classService = classService;
        this.classRepo = classRepo;
        this.classAccess = classAccess;
        this.participantService = participantService;
        this.participantRepo = participantRepo;
        this.users = users;
        this.notifications = notifications;
        this.scopes = scopes;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ================================================================== đọc

    public PageResult<RequestView> search(String status, Long classId, PageQuery page) {
        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.invalidField("status", "Trạng thái không hợp lệ.");
        }
        return requests.search(scopes.currentPrincipal(), status, classId, page);
    }

    public RequestView get(long id) {
        return requests.findView(scopes.currentPrincipal(), id).orElseThrow(RequestService::notFound);
    }

    // ================================================================== tạo

    @Transactional
    public RequestView create(long classId, CreateRequest req) {
        return createInternal(classId, req, true);
    }

    /** Như {@link #create} nhưng không gửi thông báo từng đề nghị: dùng khi gửi hàng loạt, xem {@link #notifyBatchCreated}. */
    @Transactional
    public RequestView createWithoutNotification(long classId, CreateRequest req) {
        return createInternal(classId, req, false);
    }

    /** Một thông báo gộp cho giám khảo của nội dung và mọi Super Admin sau khi Admin gửi nhiều đề nghị cùng lúc. */
    @Transactional
    public void notifyBatchCreated(long classId, long firstRequestId, int added, int removed) {
        UserPrincipal me = scopes.currentPrincipal();
        ClassRow row = classRepo.findRow(classId).orElseThrow(RequestService::notFound);
        List<Long> recipients = new ArrayList<>(classRepo.teacherIds(classId));
        recipients.addAll(jdbc.queryForList(
                "SELECT id FROM app_user WHERE role = 'SUPER_ADMIN' AND status = 'ACTIVE'", Long.class));
        recipients.removeIf(r -> r == me.userId());
        List<String> parts = new ArrayList<>();
        if (added > 0) {
            parts.add(added + " đề nghị thêm");
        }
        if (removed > 0) {
            parts.add(removed + " đề nghị gỡ");
        }
        notifications.send(recipients, NotificationService.TYPE_REQUEST_CREATED,
                "Đề nghị mới: " + row.name(),
                me.displayName() + " gửi " + String.join(" và ", parts) + " người học cho nội dung kiểm tra " + row.name()
                        + ". Mở danh sách đề nghị để xử lý.",
                NotificationService.REF_REQUEST, firstRequestId);
    }

    private RequestView createInternal(long classId, CreateRequest req, boolean notify) {
        UserPrincipal me = scopes.currentPrincipal();
        ClassRow row = classService.requireVisible(me, classId);
        ParticipantService.requireOpen(row);
        String type = req.type() == null ? null : req.type().trim();
        if (type == null || !TYPES.contains(type)) {
            throw ApiException.invalidField("type", "Loại đề nghị phải là ADD (thêm) hoặc REMOVE (gỡ).");
        }
        // Đối tượng phải nằm trong nhánh của Admin; ngoài nhánh thì 404 như thể không tồn tại.
        Target target = participantService.resolveTarget(req.userId(), req.unitId(), scopes.of(me), me);
        boolean inClass = participantRepo.findActiveByUser(classId, target.userId()).isPresent();
        if ("ADD".equals(type) && inClass) {
            throw ApiException.conflict("ALREADY_IN_CLASS", "Đối tượng này đã có trong nội dung kiểm tra.");
        }
        if ("REMOVE".equals(type) && !inClass) {
            throw ApiException.conflict("NOT_IN_CLASS", "Đối tượng này không có trong nội dung kiểm tra.");
        }
        String now = Times.now(clock);
        try {
            jdbc.update("""
                    INSERT INTO class_request (class_id, type, user_id, requested_by, requested_unit_id, status, created_at)
                    VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
                    """, classId, type, target.userId(), me.userId(), me.unitId(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_REQUEST", "Đã có một đề nghị giống hệt đang chờ duyệt.");
        }
        long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        RequestView view = requests.findView(me, id).orElseThrow();

        if (notify) {
            List<Long> recipients = new ArrayList<>(classRepo.teacherIds(classId));
            recipients.addAll(jdbc.queryForList(
                    "SELECT id FROM app_user WHERE role = 'SUPER_ADMIN' AND status = 'ACTIVE'", Long.class));
            recipients.removeIf(r -> r == me.userId());
            String action = "ADD".equals(type) ? "thêm" : "gỡ";
            notifications.send(recipients, NotificationService.TYPE_REQUEST_CREATED,
                    "Đề nghị " + action + " người học: " + row.name(),
                    view.requestedBy().fullName() + " (" + view.requestedUnitName() + ") đề nghị " + action + " "
                            + view.target().fullName() + ("ADD".equals(type) ? " vào" : " khỏi") + " nội dung kiểm tra " + row.name() + ".",
                    NotificationService.REF_REQUEST, id);
        }

        audit.record(AuditAction.REQUEST_CREATED, "class_request", id, target.unitCode(), null, summary(view));
        return view;
    }

    // ================================================================== duyệt, từ chối, hủy

    @Transactional
    public RequestView approve(long id, String note) {
        UserPrincipal me = scopes.currentPrincipal();
        Row r = requireDecidable(me, id);
        ClassRow row = classRepo.findRow(r.classId()).orElseThrow(RequestService::notFound);
        String n = validNote(note, false);

        Long participantId;
        if ("ADD".equals(r.type())) {
            ParticipantService.requireOpen(row);
            // Người duyệt (giám khảo, Super Admin) thêm được người ở mọi nhánh; phạm vi đã kiểm khi tạo đề nghị.
            UserRepository.Target t = users.findTarget(r.userId(), Scope.all(me.userId())).orElseThrow(RequestService::notFound);
            if (!"ACTIVE".equals(t.status()) || t.unitId() == null) {
                throw ApiException.conflict("TARGET_INACTIVE",
                        "Đối tượng không còn hoạt động hoặc không thuộc đơn vị nào. Hãy từ chối đề nghị này.");
            }
            participantId = participantService.insert(row, new Target(t.id(), t.unitId(), t.unitCode(), t.kind()),
                    "REQUEST", id, me);
        } else {
            ParticipantRepository.Row p = participantRepo.findActiveByUser(r.classId(), r.userId())
                    .orElseThrow(() -> ApiException.conflict("NOT_IN_CLASS",
                            "Đối tượng không còn trong nội dung kiểm tra. Hãy từ chối đề nghị này."));
            participantService.removeRow(p, n, me);
            participantId = p.id();
        }
        decide(r, "APPROVED", n, me);
        RequestView view = requests.findView(me, id).orElseThrow();
        notifications.send(List.of(r.requestedBy()), NotificationService.TYPE_REQUEST_APPROVED,
                "Đề nghị đã được duyệt: " + row.name(),
                "Đề nghị " + ("ADD".equals(r.type()) ? "thêm " : "gỡ ") + view.target().fullName() + " đã được duyệt"
                        + (n == null ? "." : ". Ghi chú: " + n),
                NotificationService.REF_REQUEST, id);
        Map<String, Object> after = summary(view);
        after.put("participantId", participantId);
        audit.record(AuditAction.REQUEST_APPROVED, "class_request", id, null, Map.of("status", "PENDING"), after);
        return view;
    }

    @Transactional
    public RequestView reject(long id, String note) {
        UserPrincipal me = scopes.currentPrincipal();
        Row r = requireDecidable(me, id);
        String n = validNote(note, true);
        decide(r, "REJECTED", n, me);
        RequestView view = requests.findView(me, id).orElseThrow();
        notifications.send(List.of(r.requestedBy()), NotificationService.TYPE_REQUEST_REJECTED,
                "Đề nghị bị từ chối: " + view.classRef().name(),
                "Đề nghị " + ("ADD".equals(r.type()) ? "thêm " : "gỡ ") + view.target().fullName()
                        + " bị từ chối. Lý do: " + n,
                NotificationService.REF_REQUEST, id);
        audit.record(AuditAction.REQUEST_REJECTED, "class_request", id, null, Map.of("status", "PENDING"), summary(view));
        return view;
    }

    @Transactional
    public RequestView cancel(long id) {
        UserPrincipal me = scopes.currentPrincipal();
        Row r = requests.findRow(me, id).orElseThrow(RequestService::notFound);
        if (r.requestedBy() != me.userId()) {
            throw ApiException.forbidden("NOT_REQUESTER", "Chỉ người tạo đề nghị mới hủy được.");
        }
        requirePending(r);
        decide(r, "CANCELLED", null, me);
        RequestView view = requests.findView(me, id).orElseThrow();
        audit.record(AuditAction.REQUEST_CANCELLED, "class_request", id, null, Map.of("status", "PENDING"), summary(view));
        return view;
    }

    // ================================================================== hỗ trợ

    private Row requireDecidable(UserPrincipal me, long id) {
        Row r = requests.findRow(me, id).orElseThrow(RequestService::notFound);
        if (!classAccess.canManage(me, r.classId())) {
            throw ApiException.forbidden("NOT_TEACHER", "Chỉ giám khảo của nội dung kiểm tra hoặc Super Admin duyệt được đề nghị.");
        }
        requirePending(r);
        return r;
    }

    private static void requirePending(Row r) {
        if (!"PENDING".equals(r.status())) {
            throw ApiException.conflict("NOT_PENDING", "Đề nghị này đã được xử lý.");
        }
    }

    private void decide(Row r, String status, String note, UserPrincipal me) {
        int n = jdbc.update("""
                UPDATE class_request SET status = ?, decided_by = ?, decided_at = ?, decision_note = ?
                WHERE id = ? AND status = 'PENDING'
                """, status, me.userId(), Times.now(clock), note, r.id());
        if (n == 0) {
            throw ApiException.conflict("NOT_PENDING", "Đề nghị này đã được xử lý.");
        }
    }

    private static String validNote(String note, boolean required) {
        Validation v = new Validation();
        String n = required ? v.required("note", note, 500, "Nhập lý do từ chối.") : v.optional("note", note, 500);
        v.throwIfAny();
        return n;
    }

    private static Map<String, Object> summary(RequestView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("classId", v.classRef().id());
        m.put("type", v.type());
        m.put("targetId", v.target().id());
        m.put("status", v.status());
        m.put("note", v.decisionNote());
        return m;
    }

    private static ApiException notFound() {
        return ApiException.notFound("Không tìm thấy đề nghị.");
    }
}
