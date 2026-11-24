package vn.trainingstats.result;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import vn.trainingstats.common.Times;
import vn.trainingstats.common.excel.ExcelBook.Column;
import vn.trainingstats.common.excel.ExcelTemplate;
import vn.trainingstats.common.excel.ExcelTemplate.Field;
import vn.trainingstats.exam.ClassService;
import vn.trainingstats.participant.ParticipantDtos.Participant;
import vn.trainingstats.participant.ParticipantRepository;
import vn.trainingstats.security.ScopeService;
import vn.trainingstats.security.UserPrincipal;

/**
 * Xuất điểm một nội dung kiểm tra ra Excel (yêu cầu ngày 2026-11-21), cùng bố cục với các file mẫu. Cùng quyền và
 * cùng dữ liệu với danh sách người học trên trang nội dung: Super Admin, giám khảo thấy cả nội dung; Admin chỉ người
 * trong nhánh mình; người học chỉ dòng của mình; người khác 404. Chỉ người chưa gỡ. Xếp loại lấy từ DB.
 */
@Service
public class ResultExportService {

    static final String SHEET = "KetQua";

    private static final Column NO = new Column("no", "STT", false);
    private static final Column CODE = new Column("code", "Mã định danh", false);
    private static final Column NAME = new Column("name", "Họ tên", false);
    private static final Column RANK = new Column("rank", "Cấp bậc", false);
    private static final Column CATEGORY = new Column("category", "Đối tượng", false);
    private static final Column UNIT = new Column("unit", "Đơn vị", false);
    private static final Column SCORE = new Column("score", "Điểm", false);
    private static final Column GRADE = new Column("grade", "Xếp loại", false);
    private static final Column STATE = new Column("state", "Tình trạng", false);
    private static final Column NOTE = new Column("note", "Ghi chú", false);
    private static final Column GRADER = new Column("grader", "Người chấm", false);
    private static final Column GRADED_AT = new Column("gradedAt", "Thời gian chấm", false);

    /** Thứ tự hiển thị xếp loại, tốt nhất trước. */
    private static final Map<String, String> GRADES = new LinkedHashMap<>();

    static {
        GRADES.put("EXCELLENT", "Giỏi");
        GRADES.put("GOOD", "Khá");
        GRADES.put("PASS", "Đạt");
        GRADES.put("FAIL", "Không đạt");
    }

    private static final DateTimeFormatter DMY_HM = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(Times.DISPLAY_ZONE);

    private final ClassService classes;
    private final ParticipantRepository participants;
    private final ScopeService scopes;
    private final Clock clock;

    public ResultExportService(ClassService classes, ParticipantRepository participants, ScopeService scopes, Clock clock) {
        this.classes = classes;
        this.participants = participants;
        this.scopes = scopes;
        this.clock = clock;
    }

    public byte[] export(long classId) {
        UserPrincipal me = scopes.currentPrincipal();
        classes.requireVisible(me, classId);
        var cls = classes.get(classId);
        List<Participant> list = participants.list(classId, classes.participantScope(me, classId), "ACTIVE");

        List<Object[]> rows = new ArrayList<>();
        int tested = 0;
        int absent = 0;
        Map<String, Map<String, Integer>> gradesByKind = new LinkedHashMap<>();
        for (Participant p : list) {
            if (p.result() != null) {
                tested++;
                gradesByKind.computeIfAbsent(p.user().kind(), k -> new LinkedHashMap<>())
                        .merge(p.result().grade(), 1, Integer::sum);
            } else if (p.absence() != null) {
                absent++;
            }
            rows.add(row(rows.size() + 1, p));
        }

        List<String> info = new ArrayList<>(ClassSheetInfo.lines(cls));
        info.add("Sĩ số: " + rows.size() + "   |   Đã kiểm tra: " + tested + "   |   Không kiểm tra: " + absent
                + "   |   Chưa kiểm tra: " + (rows.size() - tested - absent));
        // Điểm cá nhân và điểm cả đơn vị không trộn (quy tắc thống kê).
        gradesByKind.forEach((kind, counts) -> info.add(("UNIT".equals(kind) ? "Xếp loại đơn vị: " : "Xếp loại cá nhân: ")
                + String.join("   ·   ", GRADES.entrySet().stream()
                        .map(g -> g.getValue() + " " + counts.getOrDefault(g.getKey(), 0)).toList())));
        info.add("Phạm vi: " + scopeText(cls.access().manage(), me)
                + "   |   Người xuất: " + me.displayName() + "   |   Thời gian xuất: " + DMY_HM.format(clock.instant()));

        List<Field> fields = List.of(
                Field.value(NO, 6), Field.value(CODE, 16), Field.value(NAME, 28), Field.value(RANK, 14),
                Field.value(CATEGORY, 10), Field.value(UNIT, 26), Field.scoreValue(SCORE, 8), Field.value(GRADE, 11),
                Field.value(STATE, 15), Field.value(NOTE, 30), Field.value(GRADER, 24), Field.value(GRADED_AT, 17));
        try (ExcelTemplate t = new ExcelTemplate("Kết quả kiểm tra")) {
            var data = t.dataSheet(SHEET, "Kết quả kiểm tra: " + cls.name(), info, fields,
                    "File xuất từ hệ thống, chỉ để xem và in; sửa trong file không đổi dữ liệu. Xếp loại: dưới 5.0 "
                            + "Không đạt, 5.0 đến 6.5 Đạt, 6.6 đến 7.9 Khá, từ 8.0 Giỏi. Nhập điểm dùng file mẫu nhập điểm.",
                    rows.size());
            t.fill(data, rows);
            return t.toBytes();
        }
    }

    private static String scopeText(boolean wholeClass, UserPrincipal me) {
        if (wholeClass) {
            return "toàn bộ người học của nội dung";
        }
        return me.isAdmin() ? "người học thuộc đơn vị bạn quản lý" : "kết quả của bạn";
    }

    /** Một dòng của bảng kết quả, theo thứ tự cột của {@code fields}. */
    private static Object[] row(int no, Participant p) {
        var r = p.result();
        String state;
        String note = null;
        if (r != null) {
            state = "Đã kiểm tra";
            note = r.edited() ? "Điểm đã được Super Admin sửa" : null;
        } else if (p.absence() != null) {
            state = "Không kiểm tra";
            note = p.absence().note() == null ? p.absence().reasonName()
                    : p.absence().reasonName() + ": " + p.absence().note();
        } else {
            state = "Chưa kiểm tra";
        }
        return new Object[] {
                no,
                ResultImportService.key(p),
                p.user().fullName(),
                "UNIT".equals(p.user().kind()) ? "Cả đơn vị" : p.user().rankName(),
                p.user().category(),
                p.snapshotUnit().name(),
                r == null ? null : r.scoreX10() / 10.0,
                r == null ? null : GRADES.getOrDefault(r.grade(), r.grade()),
                state,
                note,
                r == null ? null : r.gradedByName(),
                r == null ? null : DMY_HM.format(Times.parse(r.gradedAt()))};
    }
}
