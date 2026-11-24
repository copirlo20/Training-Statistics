package vn.trainingstats.assignment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import vn.trainingstats.assignment.AssignmentDtos.ApplyRequest;
import vn.trainingstats.assignment.AssignmentDtos.ApplyResult;
import vn.trainingstats.assignment.AssignmentDtos.Change;
import vn.trainingstats.assignment.AssignmentDtos.Column;
import vn.trainingstats.assignment.AssignmentDtos.ItemResult;
import vn.trainingstats.assignment.AssignmentDtos.Matrix;
import vn.trainingstats.assignment.AssignmentDtos.MatrixQuery;
import vn.trainingstats.assignment.AssignmentDtos.PeriodRef;
import vn.trainingstats.assignment.AssignmentDtos.Row;
import vn.trainingstats.assignment.AssignmentDtos.UnitOption;
import vn.trainingstats.assignment.AssignmentDtos.Viewer;
import vn.trainingstats.assignment.AssignmentRepository.ContentRow;
import vn.trainingstats.assignment.AssignmentRepository.Participation;
import vn.trainingstats.assignment.AssignmentRepository.Pending;
import vn.trainingstats.assignment.AssignmentRepository.PersonRow;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.PageQuery;
import vn.trainingstats.exam.ClassRepository;
import vn.trainingstats.exam.ClassRepository.ClassRow;
import vn.trainingstats.exam.PeriodService;
import vn.trainingstats.participant.ParticipantDtos.AddRequest;
import vn.trainingstats.participant.ParticipantRepository;
import vn.trainingstats.participant.ParticipantService;
import vn.trainingstats.request.RequestDtos.CreateRequest;
import vn.trainingstats.request.RequestDtos.RequestView;
import vn.trainingstats.request.RequestService;
import vn.trainingstats.security.ClassAccess;
import vn.trainingstats.security.Scope;
import vn.trainingstats.security.ScopeService;
import vn.trainingstats.security.UserPrincipal;
import vn.trainingstats.unit.UnitService;
import vn.trainingstats.user.Category;

/**
 * Bảng phân công: người (hàng, nhóm theo đơn vị) x nội dung kiểm tra của một đợt (cột), xem ARCHITECTURE 3.12.
 * <ul>
 *   <li>Super Admin và giám khảo (của ít nhất một nội dung trong đợt) xem toàn bộ danh sách người. Admin chỉ xem người
 *       thuộc nhánh mình. Người dùng thường không phải giám khảo nhận 404.</li>
 *   <li>Cột: Super Admin mọi nội dung của đợt; giám khảo nội dung mình được gán; Admin mọi nội dung đang mở.</li>
 *   <li>Áp dụng thay đổi: Super Admin, giám khảo của nội dung thêm, gỡ thẳng; Admin gửi đề nghị (người phải trong nhánh).
 *       Mỗi thay đổi đi qua đúng service của chức năng gốc nên mọi kiểm tra, nhật ký giữ nguyên.</li>
 * </ul>
 * Không chạy trong một giao dịch chung: mỗi thay đổi có giao dịch ghi riêng, một thay đổi lỗi không làm hỏng các thay đổi khác.
 */
@Service
public class AssignmentService {

    static final int MAX_CHANGES = 300;
    static final int MAX_PAGE_SIZE = 200;

    private final AssignmentRepository repo;
    private final ClassRepository classRepo;
    private final ClassAccess classAccess;
    private final PeriodService periods;
    private final UnitService units;
    private final ParticipantService participants;
    private final ParticipantRepository participantRepo;
    private final RequestService requests;
    private final ScopeService scopes;

    public AssignmentService(AssignmentRepository repo, ClassRepository classRepo, ClassAccess classAccess,
                             PeriodService periods, UnitService units, ParticipantService participants,
                             ParticipantRepository participantRepo, RequestService requests, ScopeService scopes) {
        this.repo = repo;
        this.classRepo = classRepo;
        this.classAccess = classAccess;
        this.periods = periods;
        this.units = units;
        this.participants = participants;
        this.participantRepo = participantRepo;
        this.requests = requests;
        this.scopes = scopes;
    }

    // ================================================================== đọc

    /** Bảng phân công một trang. Đơn vị lọc ngoài phạm vi thì 404. */
    public Matrix matrix(MatrixQuery query) {
        if (query.periodId() == null) {
            throw ApiException.invalidField("periodId", "Chọn đợt kiểm tra.");
        }
        long periodId = query.periodId();
        UserPrincipal me = scopes.currentPrincipal();
        PeriodService.PeriodView period = periods.find(periodId).orElseThrow(AssignmentService::periodNotFound);
        Scope scope = visibleScope(me, periodId);
        boolean full = scope.kind() == Scope.Kind.ALL;

        List<String> unitCodes = new ArrayList<>();
        for (Long unitId : query.unitId() == null ? List.<Long>of() : query.unitId()) {
            if (unitId != null) {
                unitCodes.add(unitInScope(scope, unitId).code());
            }
        }
        if (query.unit() != null) {
            unitInScope(scope, query.unit());
        }
        List<Long> specialtyFilter = query.specialtyId() == null ? List.of()
                : query.specialtyId().stream().filter(Objects::nonNull).distinct().toList();
        PageQuery pq = pageQuery(query.page(), query.size());

        List<Column> columns = columns(me, periodId, Boolean.TRUE.equals(query.includeClosed()));
        List<Long> classIds = columns.stream().map(Column::id).toList();
        Set<Long> teaching = Set.copyOf(repo.teachingClassIds(me.userId(), classIds));
        AssignmentRepository.Page people = repo.people(scope, new AssignmentRepository.Filter(unitCodes, query.unit(),
                specialtyFilter, categories(query.category()), query.q()), pq);
        List<Long> userIds = people.rows().stream().map(PersonRow::userId).toList();

        Map<Long, List<String>> specialties = repo.specialties(userIds);
        Map<Long, Map<Long, String>> cells = new HashMap<>();
        for (Participation p : repo.participations(classIds, userIds)) {
            cells.computeIfAbsent(p.userId(), k -> new LinkedHashMap<>()).put(p.classId(), p.graded() ? "IN_GRADED" : "IN");
        }
        // Đề nghị đang chờ: Super Admin thấy hết; người khác chỉ thấy do mình tạo hoặc của nội dung mình làm giám khảo.
        for (Pending p : repo.pending(classIds, userIds)) {
            if (me.isSuperAdmin() || p.requestedBy() == me.userId() || teaching.contains(p.classId())) {
                cells.computeIfAbsent(p.userId(), k -> new LinkedHashMap<>())
                        .put(p.classId(), "ADD".equals(p.type()) ? "PENDING_ADD" : "PENDING_REMOVE");
            }
        }
        List<Row> rows = new ArrayList<>();
        for (PersonRow r : people.rows()) {
            rows.add(new Row(r.userId(), r.fullName(), r.rankSymbol(), r.personalCode(), r.category(), r.unitId(), r.unitCode(),
                    r.unitName(), r.parentUnitName(), specialties.getOrDefault(r.userId(), List.of()),
                    cells.getOrDefault(r.userId(), Map.of())));
        }
        return new Matrix(new PeriodRef(period.id(), period.name(), period.status()),
                new Viewer(full, full ? null : scopeUnitName(scope)), columns, rows, pq.page(), pq.size(), people.total());
    }

    private UnitService.UnitView unitInScope(Scope scope, long unitId) {
        return units.find(unitId).filter(u -> scope.coversUnit(u.code()))
                .orElseThrow(() -> ApiException.notFound("Không tìm thấy đơn vị."));
    }

    /**
     * Cột của bảng: các nội dung của đợt người gọi thấy, kèm chế độ. Super Admin, giám khảo của nội dung: DIRECT; Admin:
     * REQUEST; nội dung đã đóng (hoặc đợt đã khóa): VIEW. Dùng chung cho bảng và file Excel.
     */
    List<Column> columns(UserPrincipal me, long periodId, boolean includeClosed) {
        List<ContentRow> contents = repo.contents(periodId, includeClosed, me.userId(), me.isSuperAdmin(),
                me.isAdmin() ? scopes.of(me) : null);
        Set<Long> teaching = Set.copyOf(repo.teachingClassIds(me.userId(), contents.stream().map(ContentRow::id).toList()));
        List<Column> columns = new ArrayList<>();
        for (ContentRow c : contents) {
            // Nội dung đã đóng chỉ xem: không nhận thêm người hay đề nghị mới.
            String mode = !"OPEN".equals(c.status()) ? "VIEW"
                    : me.isSuperAdmin() || teaching.contains(c.id()) ? "DIRECT" : me.isAdmin() ? "REQUEST" : "VIEW";
            columns.add(new Column(c.id(), c.name(), c.examDate(), c.location(), c.status(), mode));
        }
        return columns;
    }

    static List<String> categories(List<String> raw) {
        List<String> out = raw == null ? List.of()
                : raw.stream().filter(c -> c != null && !c.isBlank()).map(String::trim).distinct().toList();
        if (out.stream().anyMatch(c -> !Category.CODES.contains(c))) {
            throw ApiException.invalidField("category", "Đối tượng phải là SQ, QNCN hoặc HSQ-CS.");
        }
        return out;
    }

    /** Đơn vị trong phạm vi người gọi, cho bộ lọc đơn vị. */
    public List<UnitOption> unitOptions(long periodId) {
        UserPrincipal me = scopes.currentPrincipal();
        periods.find(periodId).orElseThrow(AssignmentService::periodNotFound);
        return repo.units(visibleScope(me, periodId));
    }

    // ================================================================== ghi

    public ApplyResult apply(ApplyRequest req) {
        UserPrincipal me = scopes.currentPrincipal();
        List<Change> changes = req == null || req.changes() == null ? List.of() : req.changes();
        if (changes.isEmpty()) {
            throw ApiException.invalidField("changes", "Chưa có thay đổi nào.");
        }
        if (changes.size() > MAX_CHANGES) {
            throw ApiException.invalidField("changes", "Tối đa " + MAX_CHANGES + " thay đổi mỗi lần.");
        }
        List<ItemResult> results = new ArrayList<>();
        CreatedRequests created = new CreatedRequests();
        int applied = 0;
        int requested = 0;
        for (Change ch : changes) {
            ItemResult r;
            try {
                r = applyOne(me, ch, created);
            } catch (ApiException e) {
                r = new ItemResult(ch.classId(), ch.userId(), ch.action(), "ERROR", e.getMessage());
            }
            switch (r.status()) {
                case "APPLIED" -> applied++;
                case "REQUESTED" -> requested++;
                default -> {
                }
            }
            results.add(r);
        }
        notifyCreated(created);
        return new ApplyResult(results, applied, requested, results.size() - applied - requested);
    }

    /** Đề nghị đã tạo trong một lần phân công, gom theo nội dung để gửi một thông báo gộp thay cho từng đề nghị. */
    static final class CreatedRequests {
        private record Item(long requestId, boolean add) {
        }

        private final Map<Long, List<Item>> byClass = new LinkedHashMap<>();

        void add(long classId, long requestId, boolean add) {
            byClass.computeIfAbsent(classId, k -> new ArrayList<>()).add(new Item(requestId, add));
        }
    }

    /** Mỗi nội dung có đề nghị mới nhận một thông báo gộp (số đề nghị thêm, gỡ). */
    void notifyCreated(CreatedRequests created) {
        created.byClass.forEach((classId, items) -> {
            int added = (int) items.stream().filter(CreatedRequests.Item::add).count();
            requests.notifyBatchCreated(classId, items.get(0).requestId(), added, items.size() - added);
        });
    }

    /** Một thay đổi, đi qua đúng service gốc. Dùng chung cho bảng (giao dịch riêng) và nhập Excel (chung giao dịch). */
    ItemResult applyOne(UserPrincipal me, Change ch, CreatedRequests created) {
        if (ch == null || ch.classId() == null || ch.userId() == null) {
            throw ApiException.badRequest("INVALID_CHANGE", "Thay đổi thiếu nội dung kiểm tra hoặc người.");
        }
        String action = ch.action() == null ? "" : ch.action().trim();
        if (!Set.of("ADD", "REMOVE").contains(action)) {
            throw ApiException.badRequest("INVALID_CHANGE", "Thao tác phải là ADD (thêm) hoặc REMOVE (gỡ).");
        }
        ClassRow row = classRepo.findRow(ch.classId()).orElse(null);
        boolean direct = row != null && classAccess.canManage(me, row.id());
        if (row == null || (!direct && !me.isAdmin())) {
            throw ApiException.notFound("Không tìm thấy nội dung kiểm tra.");
        }
        if (direct) {
            if ("ADD".equals(action)) {
                participants.add(row.id(), new AddRequest(ch.userId(), null));
            } else {
                var p = participantRepo.findActiveByUser(row.id(), ch.userId())
                        .orElseThrow(() -> ApiException.conflict("NOT_IN_CLASS", "Người này không có trong nội dung kiểm tra."));
                if (p.hasResult()) {
                    throw ApiException.conflict("HAS_RESULT",
                            "Người này đã có điểm: gỡ ở trang nội dung kiểm tra, kèm lý do (chỉ Super Admin).");
                }
                participants.remove(row.id(), p.id(), null);
            }
            return new ItemResult(row.id(), ch.userId(), action, "APPLIED", null);
        }
        RequestView v = requests.createWithoutNotification(row.id(), new CreateRequest(action, ch.userId(), null));
        created.add(row.id(), v.id(), "ADD".equals(action));
        return new ItemResult(row.id(), ch.userId(), action, "REQUESTED", null);
    }

    // ================================================================== hỗ trợ

    /**
     * Phạm vi danh sách người: Super Admin và giám khảo của đợt xem toàn Lữ đoàn, Admin xem nhánh mình. Người khác 404,
     * như thể chức năng không có với họ.
     */
    Scope visibleScope(UserPrincipal me, long periodId) {
        if (me.isSuperAdmin() || repo.isTeacherInPeriod(me.userId(), periodId)) {
            return Scope.all(me.userId());
        }
        if (me.isAdmin()) {
            return scopes.of(me);
        }
        throw periodNotFound();
    }

    private String scopeUnitName(Scope scope) {
        return units.tree().stream().filter(u -> u.code().equals(scope.unitCode())).map(UnitService.UnitView::name)
                .findFirst().orElse(scope.unitCode());
    }

    private static PageQuery pageQuery(Integer page, Integer size) {
        int s = size == null ? 100 : size;
        if (s < 1 || s > MAX_PAGE_SIZE) {
            throw ApiException.invalidField("size", "Số dòng mỗi trang phải từ 1 đến " + MAX_PAGE_SIZE + ".");
        }
        int p = page == null ? 0 : page;
        if (p < 0) {
            throw ApiException.invalidField("page", "Số trang không được âm.");
        }
        return new PageQuery(p, s);
    }

    private static ApiException periodNotFound() {
        return ApiException.notFound("Không tìm thấy đợt kiểm tra.");
    }
}
