package vn.trainingstats.assignment;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.trainingstats.assignment.AssignmentDtos.Change;
import vn.trainingstats.assignment.AssignmentDtos.Column;
import vn.trainingstats.assignment.AssignmentRepository.Participation;
import vn.trainingstats.assignment.AssignmentRepository.Pending;
import vn.trainingstats.assignment.AssignmentRepository.PersonRow;
import vn.trainingstats.audit.AuditAction;
import vn.trainingstats.audit.AuditService;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.PageQuery;
import vn.trainingstats.common.Times;
import vn.trainingstats.common.Validation;
import vn.trainingstats.common.excel.ContentRows;
import vn.trainingstats.common.excel.ExcelBook;
import vn.trainingstats.common.excel.ExcelBook.DataRow;
import vn.trainingstats.common.excel.ExcelTemplate;
import vn.trainingstats.common.excel.ExcelTemplate.Field;
import vn.trainingstats.common.excel.ImportReport;
import vn.trainingstats.common.excel.ImportRunner;
import vn.trainingstats.common.excel.PersonColumns;
import vn.trainingstats.common.excel.RowKeys;
import vn.trainingstats.exam.PeriodService;
import vn.trainingstats.security.Scope;
import vn.trainingstats.security.ScopeService;
import vn.trainingstats.security.UserPrincipal;
import vn.trainingstats.unit.UnitService;
import vn.trainingstats.unit.UnitService.UnitView;

/**
 * Phân công người học bằng Excel (yêu cầu ngày 2026-11-20), cùng quyền và quy tắc với bảng phân công:
 * <ul>
 *   <li>File mẫu: mỗi đơn vị một sheet, tính theo đơn vị nhỏ nhất (người thuộc đúng đơn vị đó, không gồm đơn vị con).
 *       Chỉ có đơn vị và người trong phạm vi người tải (Super Admin, giám khảo của đợt: toàn Lữ đoàn; Admin: nhánh
 *       mình). Cột là các nội dung đang mở người tải thao tác được: Super Admin mọi nội dung, giám khảo nội dung mình,
 *       Admin mọi nội dung (gửi đề nghị). Ô ghi "x" là người đó tham gia nội dung.</li>
 *   <li>Nhập: so file với tình trạng hiện tại. "x" ở người chưa tham gia là thêm, ô trống ở người đang tham gia là gỡ;
 *       Super Admin, giám khảo làm ngay, Admin gửi đề nghị. Người đã có điểm không gỡ bằng file; ô đang chờ duyệt bị
 *       bỏ qua. Ghi tất cả hoặc không ghi gì, trong một giao dịch, mỗi thay đổi đi qua đúng service gốc.</li>
 * </ul>
 * Người được khớp theo mã định danh nên tên sheet chỉ để đọc; sheet nào cũng được nhận.
 */
@Service
public class AssignmentExcelService {

    private static final String MARK = "x";
    private static final String IN = "IN";
    private static final String IN_GRADED = "IN_GRADED";
    private static final String PENDING_ADD = "PENDING_ADD";
    private static final String PENDING_REMOVE = "PENDING_REMOVE";
    private static final String DIRECT = "DIRECT";
    private static final String ADD = "ADD";
    private static final String REMOVE = "REMOVE";
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd/MM");

    /** Một thay đổi sẽ ghi, kèm vị trí trong file để báo lỗi đúng chỗ. */
    private record Planned(String sheet, int row, String who, String header, Change change) {
    }

    /** Các dòng của một sheet đơn vị và vị trí ô bị khóa (đã có điểm, đang chờ duyệt). */
    private record SheetRows(List<Object[]> rows, List<int[]> locked) {
    }

    private final AssignmentService assignments;
    private final AssignmentRepository repo;
    private final PeriodService periods;
    private final UnitService units;
    private final ScopeService scopes;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AssignmentExcelService(AssignmentService assignments, AssignmentRepository repo, PeriodService periods,
                                  UnitService units, ScopeService scopes, AuditService audit,
                                  PlatformTransactionManager txManager, Clock clock) {
        this.assignments = assignments;
        this.repo = repo;
        this.periods = periods;
        this.units = units;
        this.scopes = scopes;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    // ================================================================== file mẫu

    public byte[] template(long periodId) {
        Context ctx = context(periodId);
        List<Column> cols = ctx.cols();
        List<PersonRow> people = people(ctx.scope());
        if (people.isEmpty()) {
            throw ApiException.conflict("NO_PEOPLE", "Không có người nào trong phạm vi bạn phân công được.");
        }
        Map<Long, Map<Long, String>> state = state(cols);
        Map<Long, UnitView> unitById = new HashMap<>();
        units.tree().forEach(u -> unitById.put(u.id(), u));

        // Giữ thứ tự cây đơn vị của danh sách người (đã sắp theo đơn vị rồi tên).
        Map<Long, List<PersonRow>> byUnit = new LinkedHashMap<>();
        for (PersonRow p : people) {
            byUnit.computeIfAbsent(p.unitId(), k -> new ArrayList<>()).add(p);
        }
        Map<String, Integer> nameCount = new HashMap<>();
        byUnit.values().forEach(list -> nameCount.merge(list.get(0).unitName(), 1, Integer::sum));
        String how = howApplied(cols);
        String today = Times.todayText(clock);

        try (ExcelTemplate t = new ExcelTemplate("Mẫu phân công người học")) {
            List<Field> firstFields = null;
            for (List<PersonRow> list : byUnit.values()) {
                PersonRow first = list.get(0);
                UnitView unit = unitById.get(first.unitId());
                String code = unit == null ? String.valueOf(first.unitId()) : unit.code();
                String path = first.parentUnitName() == null ? first.unitName()
                        : first.parentUnitName() + " / " + first.unitName();
                SheetRows sheet = sheetRows(list, cols, state);
                var data = t.dataSheet(t.unitSheetName(first.unitName(), code, nameCount.get(first.unitName()) > 1),
                        "Phân công người học: " + first.unitName(),
                        List.of("Đợt kiểm tra: " + ctx.period().name() + "   |   Đơn vị: " + path
                                        + (unit == null ? "" : " (" + unit.code() + ")") + "   |   Số người: " + list.size(),
                                "Người tạo file: " + ctx.me().displayName() + "   |   Ngày tạo: " + today),
                        fields(cols, ctx.headers()),
                        "Ghi x vào ô của nội dung người đó tham gia; để trống là không tham gia (người đang tham gia sẽ bị gỡ). "
                                + how + " Ô xám bị khóa: người đã có điểm hoặc đang chờ duyệt.", sheet.rows().size());
                t.fill(data, sheet.rows());
                sheet.locked().forEach(cell -> t.lock(data, cell[0], cell[1]));
                t.protect(data);
                if (firstFields == null) {
                    firstFields = data.fields();
                }
            }
            t.guide("Hướng dẫn phân công bằng Excel", List.of(
                    "File gồm " + byUnit.size() + " sheet, mỗi sheet một đơn vị trong phạm vi của bạn, cột là các nội dung kiểm tra "
                            + "đang mở của đợt " + ctx.period().name() + " mà bạn phân công được."),
                    List.of(new ExcelTemplate.GuideSection("Các cột của mỗi sheet đơn vị", firstFields)), List.of(
                            "Mỗi sheet là một đơn vị (đơn vị nhỏ nhất: người thuộc đúng đơn vị đó, không gồm đơn vị con). Người được khớp theo mã định danh, nên đổi tên sheet hay thứ tự dòng không ảnh hưởng.",
                            "Chỉ ghi x hoặc để trống. Ghi x ở người chưa tham gia là thêm; xóa x ở người đang tham gia là gỡ.",
                            "Người đã có điểm không gỡ bằng file (gỡ trên trang nội dung kiểm tra, chỉ Super Admin, kèm lý do). Ô đang chờ duyệt bị bỏ qua.",
                            how,
                            "Danh sách người và nội dung là của lúc tải file. Có người hay nội dung mới thì tải lại file mẫu.",
                            "Trên giao diện, bấm \"Kiểm tra file\" trước. Có một ô lỗi thì không ghi thay đổi nào."));
            return t.toBytes();
        }
    }

    private static String howApplied(List<Column> cols) {
        boolean anyDirect = cols.stream().anyMatch(c -> DIRECT.equals(c.mode()));
        boolean anyRequest = cols.stream().anyMatch(c -> "REQUEST".equals(c.mode()));
        if (!anyRequest) {
            return "Khi nhập, các thay đổi có hiệu lực ngay.";
        }
        return anyDirect ? "Khi nhập, nội dung \"thêm ngay\" có hiệu lực ngay, nội dung khác gửi đề nghị chờ duyệt."
                : "Khi nhập, mỗi thay đổi được gửi thành đề nghị cho giám khảo hoặc Super Admin duyệt.";
    }

    private static SheetRows sheetRows(List<PersonRow> people, List<Column> cols, Map<Long, Map<Long, String>> state) {
        List<Object[]> rows = new ArrayList<>();
        List<int[]> locked = new ArrayList<>();
        for (PersonRow p : people) {
            Object[] values = new Object[PersonColumns.COUNT + cols.size()];
            values[0] = rows.size() + 1;
            values[1] = p.personalCode();
            values[2] = p.fullName();
            values[3] = p.rankName();
            values[4] = p.category();
            Map<Long, String> cells = state.getOrDefault(p.userId(), Map.of());
            for (int c = 0; c < cols.size(); c++) {
                String s = cells.get(cols.get(c).id());
                if (s == null) {
                    continue;
                }
                values[PersonColumns.COUNT + c] = switch (s) {
                    case PENDING_ADD -> "Chờ duyệt thêm";
                    case PENDING_REMOVE -> "Chờ duyệt gỡ";
                    default -> MARK;
                };
                if (!IN.equals(s)) {
                    locked.add(new int[] {rows.size(), PersonColumns.COUNT + c});
                }
            }
            rows.add(values);
        }
        return new SheetRows(rows, locked);
    }

    private static List<Field> fields(List<Column> cols, Map<Long, String> headers) {
        List<Field> fields = PersonColumns.fields("Điền sẵn, không sửa: dùng để khớp người.",
                "Điền sẵn: SQ, QNCN hoặc HSQ-CS.");
        for (Column c : cols) {
            String header = headers.get(c.id());
            String format = DIRECT.equals(c.mode())
                    ? "Ghi x: người này tham gia (thêm ngay khi nhập). Để trống: không tham gia (gỡ ngay nếu đang có)."
                    : "Ghi x: đề nghị thêm người này. Để trống ở người đang tham gia: đề nghị gỡ. Giám khảo hoặc Super Admin duyệt.";
            fields.add(Field.list(PersonColumns.contentColumn(c.id(), header), Math.max(12, Math.min(24, header.length() + 2)),
                    List.of(MARK), format, MARK));
        }
        return fields;
    }

    // ================================================================== nhập

    public ImportReport importFile(long periodId, MultipartFile file, boolean dryRun) {
        // Kiểm quyền trước khi đọc file: người không có quyền với đợt nhận 404.
        Context ctx = context(periodId);
        ExcelBook.DataSheets data = ExcelBook.readDataSheets(file, PersonColumns.readColumns(ctx.headers()));
        return ImportRunner.run(tx, dryRun, report -> plan(ctx, data.sheets(), report),
                (planned, report) -> write(ctx.me(), ctx.period(), planned, data.fileName(), report));
    }

    /** Người đang đăng nhập, đợt, phạm vi và các cột (nội dung người đó phân công được); dựng ra là đã kiểm quyền với đợt. */
    private record Context(UserPrincipal me, PeriodService.PeriodView period, Scope scope, List<Column> cols,
                           Map<Long, String> headers) {
    }

    private Context context(long periodId) {
        UserPrincipal me = scopes.currentPrincipal();
        PeriodService.PeriodView period = periods.require(periodId);
        Scope scope = assignments.visibleScope(me, periodId);
        List<Column> cols = actionable(me, periodId);
        return new Context(me, period, scope, cols, headers(cols));
    }

    private List<Planned> plan(Context ctx, Map<String, List<DataRow>> sheets, ImportReport.Builder report) {
        List<Column> cols = ctx.cols();
        Map<Long, String> headers = ctx.headers();
        Map<String, PersonRow> byCode = new HashMap<>();
        for (PersonRow p : people(ctx.scope())) {
            if (p.personalCode() != null) {
                byCode.put(p.personalCode().toUpperCase(), p);
            }
        }
        Map<Long, Map<Long, String>> state = state(cols);
        List<Planned> out = new ArrayList<>();
        ContentRows.forEach(sheets, cols.stream().map(Column::id).toList(), report, r -> true,
                "File không có cột nội dung kiểm tra nào đang mở của đợt này. Hãy tải lại file mẫu.",
                (sheet, r, code) -> {
                    PersonRow p = byCode.get(code.toUpperCase());
                    if (p == null) {
                        RowKeys.unknown(sheet, r, report, "Không có người mã \"" + code
                                + "\" trong phạm vi bạn phân công (hoặc người này đã bị khóa, xóa).");
                        return;
                    }
                    planRow(sheet, r, p, cols, headers, state.getOrDefault(p.userId(), Map.of()), report, out);
                });
        return out;
    }

    private static void planRow(String sheet, DataRow r, PersonRow p, List<Column> cols, Map<Long, String> headers,
                                Map<Long, String> current, ImportReport.Builder report, List<Planned> out) {
        for (Column c : cols) {
            if (!r.cells().containsKey(key(c))) {
                continue;
            }
            String header = headers.get(c.id());
            String action = action(Validation.trimToNull(r.get(key(c))), current.get(c.id()), p, sheet, r, header, report);
            if (action == null) {
                continue;
            }
            boolean direct = DIRECT.equals(c.mode());
            out.add(new Planned(sheet, r.number(), p.fullName(), header, new Change(c.id(), p.userId(), action)));
            report.item(sheet, r.number(), direct ? action : "REQUEST_" + action, p.fullName(),
                    header + " · " + describe(direct, action));
        }
    }

    /** Việc ở một ô: ADD, REMOVE, hoặc null khi không đổi, bỏ qua hay có lỗi (lỗi đã ghi vào báo cáo). */
    private static String action(String raw, String current, PersonRow p, String sheet, DataRow r, String header,
                                 ImportReport.Builder report) {
        if (PENDING_ADD.equals(current) || PENDING_REMOVE.equals(current)) {
            return null;
        }
        boolean mark = MARK.equalsIgnoreCase(raw);
        if (raw != null && !mark) {
            report.error(sheet, r.number(), header, "Chỉ ghi x hoặc để trống.");
            return null;
        }
        if (IN_GRADED.equals(current)) {
            if (!mark) {
                report.error(sheet, r.number(), header, p.fullName() + " đã có điểm ở nội dung này: "
                        + "không gỡ bằng file (gỡ trên trang nội dung kiểm tra, chỉ Super Admin).");
            }
            return null;
        }
        if (mark && current == null) {
            return ADD;
        }
        return !mark && IN.equals(current) ? REMOVE : null;
    }

    private static String describe(boolean direct, String action) {
        boolean add = ADD.equals(action);
        if (direct) {
            return add ? "thêm ngay" : "gỡ ngay";
        }
        return add ? "gửi đề nghị thêm" : "gửi đề nghị gỡ";
    }

    /** Ghi trong giao dịch đang mở. Thay đổi nào lỗi thì ghi vào báo cáo, người chạy hủy cả giao dịch. */
    private void write(UserPrincipal me, PeriodService.PeriodView period, List<Planned> planned, String fileName,
                       ImportReport.Builder report) {
        AssignmentService.CreatedRequests created = new AssignmentService.CreatedRequests();
        int applied = 0;
        int requested = 0;
        for (Planned p : planned) {
            try {
                if ("APPLIED".equals(assignments.applyOne(me, p.change(), created).status())) {
                    applied++;
                } else {
                    requested++;
                }
            } catch (ApiException e) {
                report.error(p.sheet(), p.row(), p.header(), p.who() + ": " + e.getMessage());
            }
        }
        if (report.hasErrors()) {
            return;
        }
        assignments.notifyCreated(created);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("fileName", fileName);
        summary.put("periodId", period.id());
        summary.put("applied", applied);
        summary.put("requested", requested);
        audit.record(AuditAction.ASSIGNMENTS_IMPORTED, "exam_period", period.id(), null, null, summary);
    }

    // ================================================================== hỗ trợ

    /** Nội dung đang mở người gọi thêm, gỡ hoặc gửi đề nghị được. Không có thì không làm file được. */
    private List<Column> actionable(UserPrincipal me, long periodId) {
        List<Column> cols = assignments.columns(me, periodId, false).stream()
                .filter(c -> !"VIEW".equals(c.mode())).toList();
        if (cols.isEmpty()) {
            throw ApiException.conflict("NO_CONTENT",
                    "Đợt này không có nội dung kiểm tra đang mở nào bạn phân công được.");
        }
        return cols;
    }

    /**
     * Tiêu đề cột của từng nội dung: tên kèm ngày kiểm tra; trùng thì thêm số hiệu. File mẫu và lúc nhập tính cùng một
     * cách nên cột khớp đúng nội dung. Dùng chung với file mẫu chấm điểm cả đợt.
     */
    public static Map<Long, String> headers(List<Column> cols) {
        Map<Long, String> base = new LinkedHashMap<>();
        Map<String, Integer> count = new HashMap<>();
        for (Column c : cols) {
            String h = c.name().trim() + (c.examDate() == null ? "" : " (" + LocalDate.parse(c.examDate()).format(DM) + ")");
            base.put(c.id(), h);
            count.merge(h.toLowerCase(), 1, Integer::sum);
        }
        Map<Long, String> out = new LinkedHashMap<>();
        base.forEach((id, h) -> out.put(id, count.get(h.toLowerCase()) > 1 ? h + " #" + id : h));
        return out;
    }

    private static String key(Column c) {
        return PersonColumns.contentKey(c.id());
    }

    private List<PersonRow> people(Scope scope) {
        return repo.people(scope, new AssignmentRepository.Filter(List.of(), null, List.of(), List.of(), null),
                new PageQuery(0, Integer.MAX_VALUE)).rows();
    }

    /**
     * Tình trạng từng người ở từng nội dung: IN, IN_GRADED, PENDING_ADD, PENDING_REMOVE. Đề nghị đang chờ của bất kỳ
     * ai cũng tính (không tạo trùng đề nghị).
     */
    private Map<Long, Map<Long, String>> state(List<Column> cols) {
        List<Long> ids = cols.stream().map(Column::id).toList();
        Map<Long, Map<Long, String>> out = new HashMap<>();
        for (Participation p : repo.participationsIn(ids)) {
            out.computeIfAbsent(p.userId(), k -> new HashMap<>()).put(p.classId(), p.graded() ? IN_GRADED : IN);
        }
        for (Pending p : repo.pendingIn(ids)) {
            out.computeIfAbsent(p.userId(), k -> new HashMap<>())
                    .put(p.classId(), ADD.equals(p.type()) ? PENDING_ADD : PENDING_REMOVE);
        }
        return out;
    }
}
