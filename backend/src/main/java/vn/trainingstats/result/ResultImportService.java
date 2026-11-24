package vn.trainingstats.result;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.trainingstats.audit.AuditAction;
import vn.trainingstats.audit.AuditService;
import vn.trainingstats.common.Times;
import vn.trainingstats.common.Validation;
import vn.trainingstats.common.excel.ExcelBook;
import vn.trainingstats.common.excel.ExcelBook.Column;
import vn.trainingstats.common.excel.ExcelBook.DataRow;
import vn.trainingstats.common.excel.ExcelTemplate;
import vn.trainingstats.common.excel.ExcelTemplate.Field;
import vn.trainingstats.common.excel.ImportReport;
import vn.trainingstats.common.excel.ImportRunner;
import vn.trainingstats.exam.ClassService;
import vn.trainingstats.participant.ParticipantDtos.Participant;
import vn.trainingstats.participant.ParticipantRepository;
import vn.trainingstats.security.Scope;
import vn.trainingstats.security.ScopeService;
import vn.trainingstats.security.UserPrincipal;

/**
 * Nhập điểm một lớp bằng Excel (M5.4): Super Admin và giáo viên của lớp. Quy tắc giống chấm từng người (ResultService):
 * mỗi người một điểm (dòng của người đã có điểm là lỗi, không ghi đè), không tự chấm cho mình, nhập điểm thì xóa lý
 * do vắng. Ô điểm để trống thì bỏ qua dòng đó (không bắt buộc nhập hết lớp). Ghi tất cả hoặc không ghi gì.
 */
@Service
public class ResultImportService {

    static final String SHEET = "Diem";

    static final List<Column> COLUMNS = List.of(
            new Column("code", "Mã định danh *", true),
            new Column("name", "Họ tên", false),
            new Column("unit", "Đơn vị", false),
            new Column("score", "Điểm", true));

    /** Cột chỉ để đối chiếu trong file mẫu (máy chủ không đọc). */
    private static final Column NO = new Column("no", "STT", false);
    private static final Column RANK = new Column("rank", "Cấp bậc", false);
    private static final Column STATUS = new Column("status", "Tình trạng", false);

    private static final String CODE_HEADER = "Mã định danh";
    private static final String SCORE_HEADER = "Điểm";

    private record Planned(int row, long participantId, int score) {
    }

    private final ClassService classes;
    private final ParticipantRepository participants;
    private final ResultService results;
    private final ScopeService scopes;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ResultImportService(ClassService classes, ParticipantRepository participants, ResultService results,
                               ScopeService scopes, AuditService audit, PlatformTransactionManager txManager, Clock clock) {
        this.classes = classes;
        this.participants = participants;
        this.results = results;
        this.scopes = scopes;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** Khóa khớp dòng: mã định danh của người, hoặc mã đơn vị với dòng đại diện cả đơn vị. */
    static String key(Participant p) {
        return "UNIT".equals(p.user().kind()) ? p.snapshotUnit().code() : p.user().personalCode();
    }

    /**
     * File mẫu là danh sách đầy đủ của lớp (người chưa gỡ). Các cột khớp, đối chiếu bị khóa; người đã có điểm hiện
     * sẵn điểm và khóa ô điểm (sửa điểm chỉ Super Admin, trên giao diện); dòng của chính người tải (giáo viên là
     * người học) cũng khóa ô điểm. Chỉ mở ô Điểm của người chưa có điểm.
     */
    public byte[] template(long classId) {
        UserPrincipal me = scopes.currentPrincipal();
        classes.requireManageable(me, classId);
        var cls = classes.get(classId);
        List<Object[]> rows = new ArrayList<>();
        List<Integer> lockedRows = new ArrayList<>();
        int graded = 0;
        for (Participant p : participants.list(classId, Scope.all(me.userId()), "ACTIVE")) {
            boolean self = "PERSON".equals(p.user().kind()) && p.user().id() == me.userId();
            String status;
            Object score = null;
            if (p.result() != null) {
                status = "Đã có điểm";
                score = p.result().scoreX10() / 10.0;
                graded++;
            } else if (self) {
                status = "Không tự chấm cho mình";
            } else if (p.absence() != null) {
                status = "Không kiểm tra (" + p.absence().reasonName() + ")";
            } else {
                status = "Chưa kiểm tra";
            }
            if (p.result() != null || self) {
                lockedRows.add(rows.size());
            }
            rows.add(new Object[] {rows.size() + 1, key(p), p.user().fullName(),
                    "UNIT".equals(p.user().kind()) ? "Cả đơn vị" : p.user().rankName(), p.snapshotUnit().name(), status, score});
        }
        List<String> info = new ArrayList<>(ClassSheetInfo.lines(cls));
        info.add("Sĩ số: " + rows.size() + "   |   Đã có điểm: " + graded + "   |   Chưa có điểm: " + (rows.size() - graded));
        info.add("Người tạo file: " + me.displayName() + "   |   Ngày tạo: " + Times.todayText(clock));
        List<Field> fields = List.of(
                Field.readOnly(NO, 6, "Số thứ tự, điền sẵn."),
                Field.readOnly(COLUMNS.get(0), 18, "Điền sẵn, không sửa. Mã định danh của người học; dòng cả đơn vị dùng mã đơn vị."),
                Field.readOnly(COLUMNS.get(1), 30, "Điền sẵn, chỉ để đối chiếu."),
                Field.readOnly(RANK, 14, "Điền sẵn, chỉ để đối chiếu."),
                Field.readOnly(COLUMNS.get(2), 28, "Điền sẵn: đơn vị lúc tham gia, chỉ để đối chiếu."),
                Field.readOnly(STATUS, 34, "Điền sẵn: tình trạng lúc tải file mẫu."),
                Field.score(COLUMNS.get(3), 10));
        try (ExcelTemplate t = new ExcelTemplate("Mẫu nhập điểm")) {
            var data = t.dataSheet(SHEET, "Bảng nhập điểm", info, fields,
                    "Chỉ điền cột Điểm (số từ 0 đến 10, tối đa một chữ số thập phân) cho người chưa có điểm; để trống nếu "
                            + "người đó chưa kiểm tra. Ô màu xám bị khóa: người đã có điểm chỉ Super Admin sửa trên giao diện.",
                    rows.size());
            t.fill(data, rows);
            for (int i : lockedRows) {
                t.lock(data, i, fields.size() - 1);
            }
            t.protect(data);
            t.guide("Hướng dẫn nhập điểm", List.of(
                    "Sheet " + SHEET + " là danh sách đầy đủ của nội dung kiểm tra " + cls.name() + " lúc tải file mẫu. "
                            + "Chỉ điền cột Điểm của người chưa có điểm; các ô khác bị khóa để không sửa nhầm."),
                    List.of(new ExcelTemplate.GuideSection("Các cột của sheet " + SHEET, data.fields())),
                    List.of(
                            "Để trống ô Điểm thì bỏ qua người đó, nhập sau cũng được.",
                            "Người đang ghi \"Không kiểm tra\" mà được nhập điểm thì lý do không kiểm tra bị xóa.",
                            "Mỗi người chỉ có một điểm. Người đã có điểm giữ nguyên điểm trong file thì được bỏ qua; đổi điểm của họ thì báo lỗi (sửa điểm chỉ Super Admin, trên giao diện).",
                            "Giám khảo không chấm điểm cho chính mình.",
                            "Danh sách nội dung kiểm tra thay đổi sau khi tải (thêm, gỡ người) thì tải lại file mẫu.",
                            "Trên giao diện, bấm \"Kiểm tra file\" trước. Có một dòng lỗi thì không ghi dòng nào."));
            return t.toBytes();
        }
    }

    public ImportReport importFile(long classId, MultipartFile file, boolean dryRun) {
        UserPrincipal me = scopes.currentPrincipal();
        // Kiểm quyền trước khi đọc file: người không quản lý lớp luôn nhận 404.
        classes.requireManageable(me, classId);
        List<DataRow> rows;
        String fileName;
        try (ExcelBook book = ExcelBook.open(file)) {
            rows = book.rows(SHEET, COLUMNS, true);
            fileName = book.fileName();
        }
        return ImportRunner.run(tx, dryRun, report -> plan(classId, rows, me, report),
                (planned, report) -> write(classId, planned, me, fileName));
    }

    private List<Planned> plan(long classId, List<DataRow> rows, UserPrincipal me, ImportReport.Builder report) {
        report.rows(rows.size());
        Map<String, Participant> byKey = new HashMap<>();
        for (Participant p : participants.list(classId, Scope.all(me.userId()), "ACTIVE")) {
            String k = key(p);
            if (k != null) {
                byKey.put(k.toUpperCase(), p);
            }
        }
        Map<String, Integer> seen = new HashMap<>();
        List<Planned> out = new ArrayList<>();
        for (DataRow r : rows) {
            String code = Validation.trimToNull(r.get("code"));
            String scoreText = Validation.trimToNull(r.get("score"));
            if (code == null) {
                report.error(null, r.number(), CODE_HEADER, "Thiếu mã định danh.");
                continue;
            }
            Participant p = byKey.get(code.toUpperCase());
            if (scoreText == null) {
                report.item(null, r.number(), "SKIP", p == null ? code : p.user().fullName(), "Chưa có điểm, bỏ qua.");
                continue;
            }
            Integer first = seen.putIfAbsent(code.toUpperCase(), r.number());
            if (first != null) {
                report.error(null, r.number(), CODE_HEADER, "Trùng với dòng " + first + ".");
            } else if (p == null) {
                report.error(null, r.number(), CODE_HEADER, "Không có người học mã \"" + code + "\" trong nội dung kiểm tra.");
            } else {
                Integer score = planRow(r.number(), p, scoreText, me, report);
                if (score != null) {
                    out.add(new Planned(r.number(), p.id(), score));
                    report.item(null, r.number(), "GRADE", p.user().fullName(),
                            Scores.text(score) + " · " + p.snapshotUnit().name());
                }
            }
        }
        return out;
    }

    /** Điểm sẽ ghi cho một dòng, hoặc null khi bỏ qua hay có lỗi (lỗi đã ghi vào báo cáo, kể cả lỗi cùng dòng khác). */
    private static Integer planRow(int row, Participant p, String scoreText, UserPrincipal me, ImportReport.Builder report) {
        boolean ok = true;
        if (p.result() != null) {
            // File mẫu điền sẵn điểm đã có: giữ nguyên thì bỏ qua, đổi điểm thì báo lỗi (không ghi đè).
            String old = Scores.text(p.result().scoreX10());
            Integer same = Scores.fromCellOrNull(scoreText);
            if (same != null && same == p.result().scoreX10()) {
                report.item(null, row, "SKIP", p.user().fullName(), "Đã có điểm " + old + ", giữ nguyên.");
                return null;
            }
            report.error(null, row, SCORE_HEADER, p.user().fullName() + " đã có điểm " + old
                    + ". Không ghi đè; cần sửa thì báo Super Admin.");
            ok = false;
        }
        if ("PERSON".equals(p.user().kind()) && p.user().id() == me.userId()) {
            report.error(null, row, CODE_HEADER, "Không tự chấm điểm cho chính mình.");
            ok = false;
        }
        Integer score = Scores.fromCell(scoreText, message -> report.error(null, row, SCORE_HEADER, message));
        return ok ? score : null;
    }

    private void write(long classId, List<Planned> planned, UserPrincipal me, String fileName) {
        String now = Times.now(clock);
        for (Planned p : planned) {
            ParticipantRepository.Row row = participants.findRow(classId, p.participantId(), Scope.all(me.userId()))
                    .orElseThrow();
            results.insertResult(classId, row, p.score(), me, now, "IMPORT");
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("fileName", fileName);
        summary.put("classId", classId);
        summary.put("graded", planned.size());
        audit.record(AuditAction.RESULTS_IMPORTED, "exam_class", classId, null, null, summary);
    }
}
