package vn.trainingstats.exam;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.trainingstats.audit.AuditAction;
import vn.trainingstats.audit.AuditService;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.Times;
import vn.trainingstats.common.Validation;
import vn.trainingstats.common.VietnameseText;
import vn.trainingstats.common.excel.ExcelBook;
import vn.trainingstats.common.excel.ExcelBook.Column;
import vn.trainingstats.common.excel.ExcelBook.DataRow;
import vn.trainingstats.common.excel.ExcelTemplate;
import vn.trainingstats.common.excel.ExcelTemplate.Field;
import vn.trainingstats.common.excel.ImportReport;
import vn.trainingstats.common.excel.ImportRunner;
import vn.trainingstats.exam.ClassRepository.ClassRow;
import vn.trainingstats.participant.ParticipantService;
import vn.trainingstats.security.Scope;
import vn.trainingstats.security.ScopeService;
import vn.trainingstats.security.UserPrincipal;
import vn.trainingstats.user.UserRepository;
import vn.trainingstats.user.UserRepository.CodeMatch;

/**
 * Nhập nhiều lớp vào một đợt kiểm tra (M5.3, chỉ Super Admin). Một file hai sheet: "Lop" (mỗi dòng một lớp) và
 * "NguoiHoc" (tên lớp, mã định danh). Giáo viên gán trên giao diện. Ghi tất cả hoặc không ghi gì.
 */
@Service
public class ClassImportService {

    static final String SHEET_CLASS = "NoiDung";
    static final String SHEET_PEOPLE = "NguoiHoc";
    private static final String OPEN = "OPEN";

    static final List<Column> CLASS_COLUMNS = List.of(
            new Column("name", "Tên nội dung *", true),
            // Không bắt buộc có cột: file cũ không có cột này thì dùng đợt chọn trên giao diện (tham số periodId).
            new Column("period", "Đợt kiểm tra *", false),
            new Column("examDate", "Ngày kiểm tra", false),
            new Column("location", "Địa điểm", false),
            new Column("note", "Ghi chú", false));

    static final List<Column> PEOPLE_COLUMNS = List.of(
            new Column("className", "Tên nội dung *", true),
            new Column("personalCode", "Mã định danh *", true));

    private record PlannedClass(int row, String name, long periodId, String periodName, String examDate,
                                String location, String note, List<ParticipantService.Target> people) {
    }

    private final PeriodService periods;
    private final UserRepository users;
    private final ClassRepository classes;
    private final ParticipantService participants;
    private final ScopeService scopes;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ClassImportService(PeriodService periods, UserRepository users,
                              ClassRepository classes, ParticipantService participants, ScopeService scopes,
                              AuditService audit, JdbcTemplate jdbc, PlatformTransactionManager txManager, Clock clock) {
        this.periods = periods;
        this.users = users;
        this.classes = classes;
        this.participants = participants;
        this.scopes = scopes;
        this.audit = audit;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** Nhãn của đợt trong ô chọn: tên kèm thời gian, để phân biệt hai đợt trùng tên. */
    static String periodLabel(PeriodService.PeriodView p) {
        DateTimeFormatter dmy = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        if (p.startDate() == null) {
            return p.name();
        }
        return p.name() + " (" + LocalDate.parse(p.startDate()).format(dmy)
                + (p.endDate() == null ? "" : " - " + LocalDate.parse(p.endDate()).format(dmy)) + ")";
    }

    /** Nhãn của người trong ô chọn người học: "mã - họ tên (đơn vị)". Khi đọc chỉ lấy phần mã trước " - ". */
    static String personLabel(UserRepository.PersonOption p) {
        return p.personalCode() + " - " + p.fullName() + " (" + p.unitName() + ")";
    }

    public byte[] template() {
        UserPrincipal me = scopes.currentPrincipal();
        List<String> openPeriods = periods.list().stream().filter(p -> OPEN.equals(p.status()))
                .map(ClassImportService::periodLabel).toList();
        List<String> people = users.activePeople(Scope.all(me.userId())).stream()
                .map(ClassImportService::personLabel).toList();
        String today = Times.todayText(clock);
        List<String> info = List.of("Người tạo file: " + me.displayName() + "   |   Ngày tạo: " + today,
                "Đợt kiểm tra đang mở: " + openPeriods.size() + "   |   Người dùng đang hoạt động: " + people.size());
        try (ExcelTemplate t = new ExcelTemplate("Mẫu nhập nội dung kiểm tra")) {
            var classes = t.dataSheet(SHEET_CLASS, "Danh sách nội dung kiểm tra", info, List.of(
                    Field.code(CLASS_COLUMNS.get(0), 34, 150, "Tên nội dung, tối đa 150 ký tự, không trùng với nội dung kiểm tra khác trong file.",
                            "Bắn súng bài 1"),
                    Field.list(CLASS_COLUMNS.get(1), 38, openPeriods,
                            "Chọn đợt kiểm tra đang mở trong danh sách. Mỗi nội dung kiểm tra chọn một đợt.",
                            openPeriods.isEmpty() ? "" : openPeriods.get(0)),
                    Field.date(CLASS_COLUMNS.get(2), 16, false, "Ngày/tháng/năm, có thể để trống.", "25/11/2026"),
                    Field.text(CLASS_COLUMNS.get(3), 26, 200, "Tối đa 200 ký tự.", "Thao trường 1"),
                    Field.text(CLASS_COLUMNS.get(4), 30, 500, "Tối đa 500 ký tự.", "")));
            var peopleSheet = t.dataSheet(SHEET_PEOPLE, "Danh sách người học theo nội dung kiểm tra", info, List.of(
                    Field.listFrom(PEOPLE_COLUMNS.get(0), 34, classes.columnRange(0),
                            "Chọn tên nội dung đã điền ở sheet " + SHEET_CLASS + ".", "Bắn súng bài 1"),
                    Field.list(PEOPLE_COLUMNS.get(1), 56, people,
                            "Chọn người học trong danh sách (mã - họ tên - đơn vị), gõ vài chữ đầu của mã để tìm nhanh. "
                                    + "Một người không có hai lần trong cùng nội dung kiểm tra.",
                            people.isEmpty() ? "" : people.get(0))));
            t.guide("Hướng dẫn nhập nội dung kiểm tra", List.of(
                    "Điền sheet " + SHEET_CLASS + " trước (mỗi dòng một nội dung kiểm tra), rồi sheet " + SHEET_PEOPLE
                            + " (mỗi dòng một người học của một nội dung kiểm tra). Không xóa, đổi tên hay đổi thứ tự các dòng tiêu đề."),
                    List.of(new ExcelTemplate.GuideSection("Sheet " + SHEET_CLASS, classes.fields()),
                            new ExcelTemplate.GuideSection("Sheet " + SHEET_PEOPLE, peopleSheet.fields())),
                    List.of(
                            "Đợt kiểm tra và người học lấy từ dữ liệu của hệ thống lúc tải file mẫu: bấm mũi tên ở cạnh ô để chọn, không gõ tay. "
                                    + "Danh mục thay đổi sau đó thì tải lại file mẫu.",
                            "Giám khảo và người học là cả đơn vị thêm trên trang nội dung kiểm tra sau khi nhập.",
                            "Trên giao diện, bấm \"Kiểm tra file\" trước. Có một dòng lỗi thì không ghi dòng nào.",
                            "Tối đa " + ExcelBook.MAX_ROWS + " dòng mỗi sheet, file tối đa 5 MB."));
            return t.toBytes();
        }
    }

    public ImportReport importFile(MultipartFile file, Long periodId, boolean dryRun) {
        UserPrincipal me = scopes.currentPrincipal();
        if (!me.isSuperAdmin()) {
            throw ApiException.forbidden("FORBIDDEN", "Chỉ Super Admin nhập nội dung kiểm tra.");
        }
        List<DataRow> classRows;
        List<DataRow> peopleRows;
        String fileName;
        try (ExcelBook book = ExcelBook.open(file)) {
            classRows = book.rows(SHEET_CLASS, CLASS_COLUMNS, true);
            peopleRows = book.hasSheet(SHEET_PEOPLE) ? book.rows(SHEET_PEOPLE, PEOPLE_COLUMNS, false) : List.of();
            fileName = book.fileName();
        }
        return ImportRunner.run(tx, dryRun, report -> plan(periodId, classRows, peopleRows, report),
                (planned, report) -> write(planned, me, fileName));
    }

    private List<PlannedClass> plan(Long periodId, List<DataRow> classRows, List<DataRow> peopleRows,
                                    ImportReport.Builder report) {
        report.rows(classRows.size() + peopleRows.size());
        Map<String, Integer> nameRows = new HashMap<>();
        Map<String, PlannedClass> byName = planClasses(fallbackPeriod(periodId), classRows, nameRows, report);
        planPeople(peopleRows, byName, nameRows, report);
        for (PlannedClass c : byName.values()) {
            report.item(SHEET_CLASS, c.row(), "CREATE", c.name(),
                    c.periodName() + " · " + c.people().size() + " người học");
        }
        return new ArrayList<>(byName.values());
    }

    /** Đợt mặc định (file cũ không có cột Đợt kiểm tra): kiểm ngay, sai thì báo lỗi cho cả file. */
    private PeriodService.PeriodView fallbackPeriod(Long periodId) {
        if (periodId == null) {
            return null;
        }
        PeriodService.PeriodView p = periods.find(periodId)
                .orElseThrow(() -> ApiException.invalidField("periodId", "Không tìm thấy đợt kiểm tra."));
        if (!OPEN.equals(p.status())) {
            throw ApiException.invalidField("periodId", "Đợt kiểm tra đã đóng, không thêm nội dung kiểm tra vào được.");
        }
        return p;
    }

    /** Nội dung hợp lệ của sheet nội dung, khóa là tên đã bỏ dấu. {@code nameRows} ghi dòng của mọi tên đã gặp. */
    private Map<String, PlannedClass> planClasses(PeriodService.PeriodView fallback, List<DataRow> rows,
                                                  Map<String, Integer> nameRows, ImportReport.Builder report) {
        PeriodLookup lookup = new PeriodLookup(periods.list());
        Map<String, String> column = Map.of("name", "Tên nội dung", "location", "Địa điểm", "note", "Ghi chú");
        Map<String, PlannedClass> byName = new LinkedHashMap<>();
        for (DataRow r : rows) {
            Validation v = new Validation();
            String name = v.required("name", r.get("name"), 150, "Nhập tên nội dung.");
            String location = v.optional("location", r.get("location"), 200);
            String note = v.optional("note", r.get("note"), 500);
            v.errors().forEach(e -> report.error(SHEET_CLASS, r.number(), column.get(e.field()), e.message()));
            boolean ok = v.errors().isEmpty();
            if (name != null) {
                Integer first = nameRows.putIfAbsent(VietnameseText.searchKey(name), r.number());
                if (first != null) {
                    report.error(SHEET_CLASS, r.number(), "Tên nội dung", "Trùng tên nội dung với dòng " + first + ".");
                    ok = false;
                }
            }
            PeriodService.PeriodView period = lookup.resolve(Validation.trimToNull(r.get("period")), fallback,
                    message -> report.error(SHEET_CLASS, r.number(), "Đợt kiểm tra", message));
            String dateText = Validation.trimToNull(r.get("examDate"));
            String examDate = ExcelBook.isoDate(dateText);
            if (dateText != null && examDate == null) {
                report.error(SHEET_CLASS, r.number(), "Ngày kiểm tra", "Ngày phải có dạng dd/mm/yyyy.");
                ok = false;
            }
            if (ok && period != null) {
                byName.put(VietnameseText.searchKey(name), new PlannedClass(r.number(), name, period.id(), period.name(),
                        examDate, location, note, new ArrayList<>()));
            }
        }
        return byName;
    }

    /** Tìm đợt theo nhãn trong ô chọn ("tên (thời gian)") hoặc theo tên nếu tên không lặp lại. */
    private static final class PeriodLookup {
        private final Map<String, PeriodService.PeriodView> byLabel = new HashMap<>();
        private final Map<String, List<PeriodService.PeriodView>> byName = new HashMap<>();

        PeriodLookup(List<PeriodService.PeriodView> all) {
            for (PeriodService.PeriodView p : all) {
                byLabel.put(VietnameseText.searchKey(periodLabel(p)), p);
                byName.computeIfAbsent(VietnameseText.searchKey(p.name()), k -> new ArrayList<>()).add(p);
            }
        }

        /** Đợt đang mở của dòng, hoặc null và gọi {@code onError}. Ô trống thì dùng {@code fallback}. */
        PeriodService.PeriodView resolve(String text, PeriodService.PeriodView fallback, Consumer<String> onError) {
            if (text == null) {
                if (fallback == null) {
                    onError.accept("Chọn đợt kiểm tra.");
                }
                return fallback;
            }
            String key = VietnameseText.searchKey(text);
            List<PeriodService.PeriodView> sameName = byName.getOrDefault(key, List.of());
            PeriodService.PeriodView period = byLabel.get(key);
            if (period == null && sameName.size() == 1) {
                period = sameName.get(0);
            }
            if (period == null) {
                onError.accept(sameName.size() > 1
                        ? "Có nhiều đợt tên \"" + text + "\", hãy chọn trong danh sách (có kèm thời gian)."
                        : "Không có đợt kiểm tra \"" + text + "\".");
                return null;
            }
            if (!OPEN.equals(period.status())) {
                onError.accept("Đợt \"" + period.name() + "\" đã đóng.");
                return null;
            }
            return period;
        }
    }

    /** Thêm người học hợp lệ của sheet người học vào nội dung tương ứng trong {@code byName}. */
    private void planPeople(List<DataRow> rows, Map<String, PlannedClass> byName, Map<String, Integer> nameRows,
                            ImportReport.Builder report) {
        Map<String, Integer> pairRows = new HashMap<>();
        for (DataRow r : rows) {
            String className = Validation.trimToNull(r.get("className"));
            boolean ok = true;
            PlannedClass target = null;
            if (className == null) {
                report.error(SHEET_PEOPLE, r.number(), "Tên nội dung", "Nhập tên nội dung.");
                ok = false;
            } else {
                String key = VietnameseText.searchKey(className);
                target = byName.get(key);
                if (target == null && !nameRows.containsKey(key)) {
                    report.error(SHEET_PEOPLE, r.number(), "Tên nội dung",
                            "Không có nội dung kiểm tra \"" + className + "\" ở sheet " + SHEET_CLASS + ".");
                    ok = false;
                }
            }
            ParticipantService.Target person = person(r, className, pairRows, report);
            // Nội dung có lỗi ở sheet nội dung thì không có trong byName: dòng người học không báo thêm lỗi, chỉ không ghi.
            if (ok && person != null && target != null) {
                target.people().add(person);
            }
        }
    }

    /** Người học của dòng, hoặc null nếu có lỗi (đã báo). Cột mã nhận cả dạng "mã - họ tên" của ô chọn. */
    private ParticipantService.Target person(DataRow r, String className, Map<String, Integer> pairRows,
                                             ImportReport.Builder report) {
        String code = Validation.trimToNull(r.get("personalCode"));
        if (code != null && code.contains(" - ")) {
            code = code.substring(0, code.indexOf(" - ")).trim();
        }
        if (code == null) {
            report.error(SHEET_PEOPLE, r.number(), "Mã định danh", "Nhập mã định danh.");
            return null;
        }
        CodeMatch m = users.findByCode(code).orElse(null);
        String problem = m == null ? "Không có người dùng mang mã định danh \"" + code + "\"." : personProblem(m);
        if (problem != null) {
            report.error(SHEET_PEOPLE, r.number(), "Mã định danh", problem);
            return null;
        }
        if (className != null) {
            Integer first = pairRows.putIfAbsent(VietnameseText.searchKey(className) + "|" + m.target().id(), r.number());
            if (first != null) {
                report.error(SHEET_PEOPLE, r.number(), "Mã định danh",
                        "Người này đã có trong nội dung kiểm tra \"" + className + "\" ở dòng " + first + ".");
                return null;
            }
        }
        return new ParticipantService.Target(m.target().id(), m.target().unitId(), m.target().unitCode(), "PERSON");
    }

    /** Lý do người có mã này không vào lớp được, hoặc null. Super Admin xem được mọi người nên báo rõ. */
    private static String personProblem(CodeMatch m) {
        UserRepository.Target t = m.target();
        if (!"PERSON".equals(t.kind())) {
            return "Mã định danh không hợp lệ.";
        }
        if (!"ACTIVE".equals(t.status())) {
            return m.fullName() + " đang bị khóa hoặc đã bị xóa.";
        }
        if (t.unitId() == null) {
            return "Super Admin không thuộc đơn vị nào nên không vào nội dung kiểm tra được.";
        }
        return null;
    }

    private void write(List<PlannedClass> planned, UserPrincipal me, String fileName) {
        String now = Times.now(clock);
        List<Long> ids = new ArrayList<>();
        int people = 0;
        for (PlannedClass c : planned) {
            jdbc.update("""
                    INSERT INTO exam_class (period_id, name, exam_date, location, note,
                                            created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, c.periodId(), c.name(), c.examDate(), c.location(), c.note(), now, me.userId(), now,
                    me.userId());
            long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
            ids.add(id);
            audit.record(AuditAction.CLASS_CREATED, "exam_class", id, null, null, classes.findDetail(id).orElseThrow());
            ClassRow row = classes.findRow(id).orElseThrow();
            for (ParticipantService.Target p : c.people()) {
                participants.insert(row, p, "DIRECT", null, me);
                people++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("fileName", fileName);
        summary.put("periodIds", planned.stream().map(PlannedClass::periodId).distinct().toList());
        summary.put("classIds", ids);
        summary.put("participants", people);
        audit.record(AuditAction.CLASSES_IMPORTED, "exam_class", null, null, null, summary);
    }
}
