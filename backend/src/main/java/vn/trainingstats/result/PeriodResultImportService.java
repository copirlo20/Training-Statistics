package vn.trainingstats.result;

import java.text.Collator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.trainingstats.assignment.AssignmentDtos;
import vn.trainingstats.assignment.AssignmentExcelService;
import vn.trainingstats.audit.AuditAction;
import vn.trainingstats.audit.AuditService;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.Times;
import vn.trainingstats.common.Validation;
import vn.trainingstats.common.excel.ContentRows;
import vn.trainingstats.common.excel.ExcelBook;
import vn.trainingstats.common.excel.ExcelBook.DataRow;
import vn.trainingstats.common.excel.ExcelTemplate;
import vn.trainingstats.common.excel.ExcelTemplate.CellState;
import vn.trainingstats.common.excel.ExcelTemplate.Field;
import vn.trainingstats.common.excel.ImportReport;
import vn.trainingstats.common.excel.ImportRunner;
import vn.trainingstats.common.excel.PersonColumns;
import vn.trainingstats.common.excel.RowKeys;
import vn.trainingstats.exam.ClassDtos.OpenClass;
import vn.trainingstats.exam.ClassRepository;
import vn.trainingstats.exam.PeriodService;
import vn.trainingstats.participant.ParticipantDtos.Participant;
import vn.trainingstats.participant.ParticipantRepository;
import vn.trainingstats.security.Scope;
import vn.trainingstats.security.ScopeService;
import vn.trainingstats.security.UserPrincipal;
import vn.trainingstats.unit.UnitService;
import vn.trainingstats.unit.UnitService.UnitView;

/**
 * Chấm điểm mọi nội dung kiểm tra của một đợt bằng một file Excel (yêu cầu ngày 2026-11-23), chỉ Super Admin. Bố cục
 * như file phân công: mỗi đơn vị (lúc tham gia) một sheet, hàng là người học (và dòng "Cả đơn vị" nếu đơn vị là người
 * học), cột là các nội dung của đợt, ô là điểm. Quy tắc giống nhập điểm một nội dung (ResultImportService):
 * <ul>
 *   <li>Chỉ ô của người đang có trong nội dung đó mới nhập được; ô người đã có điểm hiện sẵn điểm và bị khóa. Giữ
 *       nguyên thì bỏ qua, đổi thì báo lỗi (không ghi đè, sửa điểm trên giao diện kèm lý do).</li>
 *   <li>Ô trống thì bỏ qua. Nhập điểm thì xóa lý do không kiểm tra. Không tự chấm cho mình.</li>
 *   <li>Nội dung đã đóng (hoặc đợt đã khóa) vẫn chấm được, như chấm trên giao diện.</li>
 *   <li>Ghi tất cả hoặc không ghi gì, trong một giao dịch; mỗi điểm đi qua {@link ResultService#insertResult}.</li>
 * </ul>
 * Người được khớp theo mã định danh (dòng cả đơn vị theo mã đơn vị), nên tên sheet, thứ tự dòng chỉ để đọc.
 */
@Service
public class PeriodResultImportService {

    private static final Map<CellState, String> LEGEND = legend();

    private static Map<CellState, String> legend() {
        Map<CellState, String> m = new LinkedHashMap<>();
        m.put(CellState.INPUT, "Nền vàng: điền điểm");
        m.put(CellState.DONE, "Nền xám: đã có điểm, khóa");
        m.put(CellState.NOT_APPLICABLE, "Gạch chéo: không tham gia, khóa");
        return m;
    }

    /** Một người học (người hoặc cả đơn vị) và chỗ của họ ở từng nội dung của đợt. */
    private static final class Learner {
        final String code;
        final Participant first;
        final Map<Long, Participant> byClass = new HashMap<>();

        Learner(String code, Participant first) {
            this.code = code;
            this.first = first;
        }

        boolean unit() {
            return "UNIT".equals(first.user().kind());
        }

        String name() {
            return first.user().fullName();
        }

        /** Người học là chính người đang chấm (không tự chấm cho mình). */
        boolean is(UserPrincipal me) {
            return !unit() && first.user().id() == me.userId();
        }
    }

    private record Planned(String sheet, int row, String header, long classId, Participant participant, int score) {
    }

    /** Các dòng của một sheet đơn vị, trạng thái từng ô điểm và số ô đã có điểm, chờ chấm. */
    private record SheetRows(List<Object[]> rows, List<CellState[]> states, int graded, int open) {
    }

    private final ClassRepository classes;
    private final ParticipantRepository participants;
    private final ResultService results;
    private final PeriodService periods;
    private final UnitService units;
    private final ScopeService scopes;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PeriodResultImportService(ClassRepository classes, ParticipantRepository participants, ResultService results,
                                     PeriodService periods, UnitService units, ScopeService scopes, AuditService audit,
                                     PlatformTransactionManager txManager, Clock clock) {
        this.classes = classes;
        this.participants = participants;
        this.results = results;
        this.periods = periods;
        this.units = units;
        this.scopes = scopes;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    // ================================================================== file mẫu

    public byte[] template(long periodId) {
        UserPrincipal me = scopes.currentPrincipal();
        PeriodService.PeriodView period = periods.require(periodId);
        List<OpenClass> cols = requireClasses(periodId);
        Map<Long, String> headers = headers(cols);
        Map<String, Learner> learners = learners(me, cols);
        if (learners.isEmpty()) {
            throw ApiException.conflict("NO_PARTICIPANTS", "Các nội dung kiểm tra của đợt này chưa có người học nào.");
        }
        Map<Long, UnitView> unitById = new HashMap<>();
        Map<Long, Integer> treeOrder = new HashMap<>();
        for (UnitView u : units.tree()) {
            unitById.put(u.id(), u);
            treeOrder.put(u.id(), treeOrder.size());
        }
        Map<Long, List<Learner>> byUnit = bySheet(learners, treeOrder);
        Map<String, Integer> nameCount = new HashMap<>();
        byUnit.keySet().forEach(id -> nameCount.merge(unitName(unitById, id, byUnit), 1, Integer::sum));
        String today = Times.todayText(clock);

        try (ExcelTemplate t = new ExcelTemplate("Mẫu chấm điểm cả đợt")) {
            List<Field> firstFields = null;
            for (Map.Entry<Long, List<Learner>> e : byUnit.entrySet()) {
                UnitView unit = unitById.get(e.getKey());
                String name = unitName(unitById, e.getKey(), byUnit);
                String code = unit == null ? String.valueOf(e.getKey()) : unit.code();
                UnitView parent = unit == null || unit.parentId() == null ? null : unitById.get(unit.parentId());
                String path = parent == null ? name : parent.name() + " / " + name;
                SheetRows sheet = sheetRows(e.getValue(), cols, me);
                var data = t.dataSheet(t.unitSheetName(name, code, nameCount.get(name) > 1), "Chấm điểm: " + name,
                        List.of("Đợt kiểm tra: " + period.name() + "   |   Đơn vị lúc tham gia: " + path
                                        + (unit == null ? "" : " (" + unit.code() + ")") + "   |   Số người học: "
                                        + sheet.rows().size(),
                                "Ô đã có điểm: " + sheet.graded() + "   |   Ô chờ chấm: " + sheet.open(),
                                "Người tạo file: " + me.displayName() + "   |   Ngày tạo: " + today),
                        fields(cols, headers),
                        "Chỉ điền điểm (0 đến 10, tối đa một chữ số thập phân) vào ô NỀN VÀNG; để trống nếu chưa kiểm tra. "
                                + "Ô nền xám đã có điểm (sửa trên trang chấm điểm, kèm lý do), ô gạch chéo là người không có "
                                + "trong nội dung đó; hai loại này bị khóa.", sheet.rows().size());
                t.fill(data, sheet.rows());
                for (int i = 0; i < sheet.states().size(); i++) {
                    for (int c = 0; c < cols.size(); c++) {
                        t.mark(data, i, PersonColumns.COUNT + c, sheet.states().get(i)[c]);
                    }
                }
                t.legend(data, LEGEND);
                t.protect(data);
                if (firstFields == null) {
                    firstFields = data.fields();
                }
            }
            t.guide("Hướng dẫn chấm điểm cả đợt bằng Excel", List.of(
                    "File gồm " + byUnit.size() + " sheet, mỗi sheet một đơn vị (đơn vị của người học lúc vào nội dung kiểm tra). "
                            + "Cột là " + cols.size() + " nội dung kiểm tra của đợt " + period.name() + ", kể cả nội dung đã đóng."),
                    List.of(new ExcelTemplate.GuideSection("Các cột của mỗi sheet đơn vị", firstFields)),
                    List.of(
                            "Người được khớp theo mã định danh; dòng \"Cả đơn vị\" là điểm tập thể của đơn vị, khớp theo mã đơn vị. "
                                    + "Đổi tên sheet hay thứ tự dòng không ảnh hưởng.",
                            "Chỉ điền ô nền vàng (người có trong nội dung, chưa có điểm). Ô nền xám đã có điểm, ô gạch chéo là "
                                    + "người không có trong nội dung đó: hai loại này bị khóa. Ô vàng để trống thì bỏ qua, nhập sau cũng được.",
                            "Mỗi người một điểm ở mỗi nội dung. Ô đã có điểm giữ nguyên thì bỏ qua; đổi điểm thì báo lỗi, không ghi đè "
                                    + "(sửa trên trang chấm điểm, kèm lý do).",
                            "Người đang ghi \"Không kiểm tra\" mà được nhập điểm thì lý do không kiểm tra bị xóa.",
                            "Danh sách người học và nội dung là của lúc tải file. Thêm, gỡ người hoặc thêm nội dung thì tải lại file mẫu.",
                            "Trên giao diện, bấm \"Kiểm tra file\" trước. Có một ô lỗi thì không ghi điểm nào."));
            return t.toBytes();
        }
    }

    private static SheetRows sheetRows(List<Learner> learners, List<OpenClass> cols, UserPrincipal me) {
        List<Object[]> rows = new ArrayList<>();
        List<CellState[]> states = new ArrayList<>();
        int graded = 0;
        int open = 0;
        for (Learner l : learners) {
            Object[] values = new Object[PersonColumns.COUNT + cols.size()];
            CellState[] rowStates = new CellState[cols.size()];
            values[0] = rows.size() + 1;
            values[1] = l.code;
            values[2] = l.name();
            values[3] = l.unit() ? "Cả đơn vị" : l.first.user().rankName();
            values[4] = l.first.user().category();
            for (int c = 0; c < cols.size(); c++) {
                Participant p = l.byClass.get(cols.get(c).id());
                if (p != null && p.result() != null) {
                    values[PersonColumns.COUNT + c] = p.result().scoreX10() / 10.0;
                    rowStates[c] = CellState.DONE;
                    graded++;
                } else if (p != null && !l.is(me)) {
                    rowStates[c] = CellState.INPUT;
                    open++;
                } else {
                    rowStates[c] = CellState.NOT_APPLICABLE;
                }
            }
            rows.add(values);
            states.add(rowStates);
        }
        return new SheetRows(rows, states, graded, open);
    }

    private static List<Field> fields(List<OpenClass> cols, Map<Long, String> headers) {
        List<Field> fields = PersonColumns.fields(
                "Điền sẵn, không sửa: mã định danh của người học; dòng cả đơn vị dùng mã đơn vị.",
                "Điền sẵn: SQ, QNCN hoặc HSQ-CS; trống với dòng cả đơn vị.");
        for (OpenClass c : cols) {
            String header = headers.get(c.id());
            fields.add(Field.score(PersonColumns.contentColumn(c.id(), header), Math.max(10, Math.min(20, header.length() + 2))));
        }
        return fields;
    }

    /** Gom người học theo đơn vị lúc tham gia (của nội dung đầu tiên họ có trong đợt), theo thứ tự cây đơn vị. */
    private static Map<Long, List<Learner>> bySheet(Map<String, Learner> learners, Map<Long, Integer> treeOrder) {
        Collator vi = Collator.getInstance(Locale.forLanguageTag("vi"));
        Comparator<Learner> order = Comparator.comparing((Learner l) -> !l.unit())
                .thenComparing(l -> givenName(l.name()), vi)
                .thenComparing(Learner::name, vi)
                .thenComparing(l -> l.code);
        Map<Long, List<Learner>> grouped = new HashMap<>();
        learners.values().forEach(l -> grouped.computeIfAbsent(l.first.snapshotUnit().id(), k -> new ArrayList<>()).add(l));
        Map<Long, List<Learner>> out = new LinkedHashMap<>();
        grouped.keySet().stream()
                .sorted(Comparator.comparing((Long id) -> treeOrder.getOrDefault(id, Integer.MAX_VALUE)).thenComparing(id -> id))
                .forEach(id -> {
                    List<Learner> list = grouped.get(id);
                    list.sort(order);
                    out.put(id, list);
                });
        return out;
    }

    private static String givenName(String fullName) {
        String n = fullName == null ? "" : fullName.trim();
        int space = n.lastIndexOf(' ');
        return space < 0 ? n : n.substring(space + 1);
    }

    private static String unitName(Map<Long, UnitView> unitById, long id, Map<Long, List<Learner>> byUnit) {
        UnitView u = unitById.get(id);
        return u != null ? u.name() : byUnit.get(id).get(0).first.snapshotUnit().name();
    }

    // ================================================================== nhập

    public ImportReport importFile(long periodId, MultipartFile file, boolean dryRun) {
        UserPrincipal me = scopes.currentPrincipal();
        PeriodService.PeriodView period = periods.require(periodId);
        List<OpenClass> cols = requireClasses(periodId);
        Map<Long, String> headers = headers(cols);
        ExcelBook.DataSheets data = ExcelBook.readDataSheets(file, PersonColumns.readColumns(headers));
        return ImportRunner.run(tx, dryRun, report -> plan(me, cols, headers, data.sheets(), report),
                (planned, report) -> write(me, period, planned, data.fileName(), report));
    }

    private List<Planned> plan(UserPrincipal me, List<OpenClass> cols, Map<Long, String> headers,
                               Map<String, List<DataRow>> sheets, ImportReport.Builder report) {
        Map<String, Learner> learners = learners(me, cols);
        // Dòng không có điểm nào thì không cần mã: người dùng có thể xóa bớt dòng hay để dòng trống.
        Predicate<DataRow> anyScore = r -> cols.stream().anyMatch(c -> rawScore(r, c) != null);
        List<Planned> out = new ArrayList<>();
        ContentRows.forEach(sheets, cols.stream().map(OpenClass::id).toList(), report, anyScore,
                "File không có cột nội dung kiểm tra nào của đợt này. Hãy tải lại file mẫu.",
                (sheet, r, code) -> {
                    Learner l = learners.get(code.toUpperCase());
                    if (l == null) {
                        if (anyScore.test(r)) {
                            RowKeys.unknown(sheet, r, report, "Không có người học mã \"" + code
                                    + "\" trong các nội dung kiểm tra của đợt này (có thể đã bị gỡ).");
                        }
                        return;
                    }
                    for (OpenClass c : cols) {
                        String raw = rawScore(r, c);
                        if (raw != null) {
                            planCell(new CellRef(sheet, r.number(), headers.get(c.id()), c.id()), l, raw, me, report, out);
                        }
                    }
                });
        return out;
    }

    private static String rawScore(DataRow r, OpenClass c) {
        return Validation.trimToNull(r.get(PersonColumns.contentKey(c.id())));
    }

    /** Một ô điểm trong file: vị trí để báo lỗi và nội dung kiểm tra của cột. */
    private record CellRef(String sheet, int row, String header, long classId) {
        void error(ImportReport.Builder report, String message) {
            report.error(sheet, row, header, message);
        }
    }

    private static void planCell(CellRef at, Learner l, String raw, UserPrincipal me, ImportReport.Builder report,
                                 List<Planned> out) {
        Participant p = l.byClass.get(at.classId());
        if (p == null) {
            at.error(report, l.name() + " không có trong nội dung này.");
            return;
        }
        if (p.result() != null) {
            Integer same = Scores.fromCellOrNull(raw);
            if (same == null || same != p.result().scoreX10()) {
                at.error(report, l.name() + " đã có điểm " + Scores.text(p.result().scoreX10())
                        + ". Không ghi đè; sửa điểm trên trang chấm điểm, kèm lý do.");
            }
            return;
        }
        if (l.is(me)) {
            at.error(report, "Không tự chấm điểm cho chính mình.");
            return;
        }
        Integer score = Scores.fromCell(raw, message -> at.error(report, message));
        if (score != null) {
            out.add(new Planned(at.sheet(), at.row(), at.header(), at.classId(), p, score));
            report.item(at.sheet(), at.row(), "GRADE", l.name(), at.header() + " · " + Scores.text(score));
        }
    }

    /** Ghi trong giao dịch đang mở. Điểm nào lỗi thì ghi vào báo cáo, người chạy hủy cả giao dịch. */
    private void write(UserPrincipal me, PeriodService.PeriodView period, List<Planned> planned, String fileName,
                       ImportReport.Builder report) {
        String now = Times.now(clock);
        Map<Long, Integer> perClass = new LinkedHashMap<>();
        for (Planned p : planned) {
            try {
                ParticipantRepository.Row row = participants.findRow(p.classId(), p.participant().id(),
                        Scope.all(me.userId())).orElseThrow(() -> ApiException.notFound("Không tìm thấy người học."));
                results.insertResult(p.classId(), row, p.score(), me, now, "IMPORT");
                perClass.merge(p.classId(), 1, Integer::sum);
            } catch (ApiException e) {
                report.error(p.sheet(), p.row(), p.header(), p.participant().user().fullName() + ": " + e.getMessage());
            }
        }
        if (report.hasErrors()) {
            return;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("fileName", fileName);
        summary.put("periodId", period.id());
        summary.put("graded", planned.size());
        summary.put("gradedByClass", perClass);
        audit.record(AuditAction.RESULTS_IMPORTED, "exam_period", period.id(), null, null, summary);
    }

    // ================================================================== hỗ trợ

    private List<OpenClass> requireClasses(long periodId) {
        List<OpenClass> cols = classes.inPeriod(periodId);
        if (cols.isEmpty()) {
            throw ApiException.conflict("NO_CONTENT", "Đợt này chưa có nội dung kiểm tra nào.");
        }
        return cols;
    }

    /** Tiêu đề cột cùng cách với file phân công: tên kèm ngày kiểm tra, trùng thì thêm số hiệu. */
    private static Map<Long, String> headers(List<OpenClass> cols) {
        return AssignmentExcelService.headers(cols.stream()
                .map(c -> new AssignmentDtos.Column(c.id(), c.name(), c.examDate(), null, null, null)).toList());
    }

    /** Người học đang có (chưa gỡ) trong các nội dung của đợt, khóa là mã khớp viết hoa. */
    private Map<String, Learner> learners(UserPrincipal me, List<OpenClass> cols) {
        Map<String, Learner> out = new LinkedHashMap<>();
        for (OpenClass c : cols) {
            for (Participant p : participants.list(c.id(), Scope.all(me.userId()), "ACTIVE")) {
                String code = ResultImportService.key(p);
                if (code != null) {
                    out.computeIfAbsent(code.toUpperCase(), k -> new Learner(code, p)).byClass.put(c.id(), p);
                }
            }
        }
        return out;
    }

}
