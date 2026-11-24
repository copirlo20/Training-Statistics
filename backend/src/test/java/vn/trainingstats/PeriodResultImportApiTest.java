package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import vn.trainingstats.audit.AuditAction;

/**
 * Chấm điểm cả đợt bằng Excel (chỉ Super Admin): mỗi đơn vị lúc tham gia một sheet, cột là mọi nội dung của đợt,
 * ô của người không có trong nội dung và ô đã có điểm bị khóa; không ghi đè điểm; ghi tất cả hoặc không ghi gì.
 */
class PeriodResultImportApiTest extends M3TestBase {

    private static final String CODE_HEADER = "Mã định danh *";

    private MockHttpSession sa;
    private MockHttpSession admin;
    private long periodId;
    private long c1;
    private long c2;
    private String h1;
    private String h2;
    private long pBoth;    // 0002.0001, ở c1 và c2
    private long pOne;     // 0003, chỉ ở c1, có lý do không kiểm tra
    private long pGraded;  // 0002.0001, có điểm 7 ở c2
    private long partOne;

    @BeforeEach
    void setUp() throws Exception {
        ensureUser("m9d-admin", "ADMIN", "0002", PW, "ACTIVE");
        sa = login(SA_USERNAME, SA_PASSWORD);
        admin = login("m9d-admin", PW);
        periodId = period(sa);
        c1 = newClassIn("Bắn súng");
        c2 = newClassIn("Thể lực");
        h1 = ok(get("/api/v1/classes/" + c1), sa, null, 200).body().get("name").asText() + " (20/11)";
        h2 = ok(get("/api/v1/classes/" + c2), sa, null, 200).body().get("name").asText() + " (20/11)";
        String tag = "Cd" + System.nanoTime();
        pBoth = person(sa, "0002.0001", tag + "A");
        pOne = person(sa, "0003", tag + "B");
        pGraded = person(sa, "0002.0001", tag + "C");
        addParticipant(sa, c1, pBoth);
        addParticipant(sa, c2, pBoth);
        partOne = addParticipant(sa, c1, pOne);
        long reason = jdbc.queryForObject("SELECT id FROM absence_reason ORDER BY id LIMIT 1", Long.class);
        ok(put("/api/v1/classes/" + c1 + "/participants/" + partOne + "/absence"), sa, Map.of("reasonId", reason), 200);
        long part = addParticipant(sa, c2, pGraded);
        tx().executeWithoutResult(s -> jdbc.update("INSERT INTO result (participant_id, score_x10, graded_by) VALUES (?, 70, ?)",
                part, userIdOf(SA_USERNAME)));
        ok(post("/api/v1/classes/" + c2 + "/participants"), sa, Map.of("unitId", unitId("0003")), 201);
    }

    private long newClassIn(String name) throws Exception {
        return ok(post("/api/v1/classes"), sa, Map.of("periodId", periodId, "name", uniq(name), "examDate", "2026-11-20"), 201).id();
    }

    // ------------------------------------------------------------------ tiện ích

    private byte[] template(MockHttpSession s, int expected) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/periods/" + periodId + "/results/import-template").session(s)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expected);
        return r.getResponse().getContentAsByteArray();
    }

    private Res upload(MockHttpSession s, byte[] bytes, boolean dryRun) throws Exception {
        var b = multipart("/api/v1/periods/" + periodId + "/results/import");
        if (bytes != null) {
            b = b.file(new MockMultipartFile("file", "cham-diem.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes));
        }
        MvcResult r = mvc.perform(b.param("dryRun", String.valueOf(dryRun)).session(s).with(csrf())).andReturn();
        String text = r.getResponse().getContentAsString();
        return new Res(r.getResponse().getStatus(), text.isEmpty() ? null : json.readTree(text));
    }

    private String code(long userId) {
        return jdbc.queryForObject("SELECT personal_code FROM app_user WHERE id = ?", String.class, userId);
    }

    private Integer score(long classId, long userId) {
        List<Integer> s = jdbc.queryForList("""
                SELECT r.score_x10 FROM result r JOIN class_participant cp ON cp.id = r.participant_id
                WHERE cp.class_id = ? AND cp.user_id = ?
                """, Integer.class, classId, userId);
        return s.isEmpty() ? null : s.get(0);
    }

    private static int headerRow(Sheet s) {
        for (int r = 0; r <= 20; r++) {
            Row row = s.getRow(r);
            if (row == null) {
                continue;
            }
            for (Cell c : row) {
                if (c.getCellType() == CellType.STRING && CODE_HEADER.equals(c.getStringCellValue())) {
                    return r;
                }
            }
        }
        return -1;
    }

    private static List<String> headers(Sheet s) {
        List<String> out = new ArrayList<>();
        s.getRow(headerRow(s)).forEach(c -> out.add(c.getStringCellValue()));
        return out;
    }

    private static Row rowOf(Sheet s, String code) {
        int head = headerRow(s);
        if (head < 0) {
            return null;
        }
        int codeCol = headers(s).indexOf(CODE_HEADER);
        for (int r = head + 1; r <= s.getLastRowNum(); r++) {
            Row row = s.getRow(r);
            Cell c = row == null ? null : row.getCell(codeCol);
            if (c != null && c.getCellType() == CellType.STRING && code.equals(c.getStringCellValue())) {
                return row;
            }
        }
        return null;
    }

    private static String sheetOf(Workbook wb, String code) {
        for (Sheet s : wb) {
            if (rowOf(s, code) != null) {
                return s.getSheetName();
            }
        }
        return null;
    }

    private static Cell cell(Workbook wb, String code, String header) {
        for (Sheet s : wb) {
            Row row = rowOf(s, code);
            if (row != null) {
                int col = headers(s).indexOf(header);
                return row.getCell(col) != null ? row.getCell(col) : row.createCell(col);
            }
        }
        return null;
    }

    private static byte[] save(Workbook wb) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static Workbook open(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    private static List<String> errorCells(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.get("errors").forEach(e -> out.add(e.get("column").asText()));
        return out;
    }

    // ------------------------------------------------------------------ file mẫu

    @Test
    void fileMauMoiDonViMotSheetCotLaMoiNoiDungCuaDot() throws Exception {
        try (Workbook wb = open(template(sa, 200))) {
            assertThat(wb.getSheetName(0)).isEqualTo("HuongDan");
            assertThat(sheetOf(wb, code(pBoth))).isEqualTo("Ban Quân huấn");
            assertThat(sheetOf(wb, code(pGraded))).isEqualTo("Ban Quân huấn");
            assertThat(sheetOf(wb, code(pOne))).isEqualTo("Phòng Chính trị");
            assertThat(sheetOf(wb, "0003")).as("dòng cả đơn vị khớp theo mã đơn vị").isEqualTo("Phòng Chính trị");
            assertThat(wb.getSheet("Phòng Tham mưu")).as("đơn vị không có người học thì không có sheet").isNull();
            Sheet s = wb.getSheet("Ban Quân huấn");
            assertThat(headers(s)).containsSubsequence("STT", CODE_HEADER, "Họ tên", "Cấp bậc", "Đối tượng", h1, h2);
            assertThat(s.getProtect()).isTrue();

            assertThat(cell(wb, code(pBoth), h1).getCellStyle().getLocked()).as("ô chờ chấm mở").isFalse();
            Cell graded = cell(wb, code(pGraded), h2);
            assertThat(graded.getNumericCellValue()).isEqualTo(7.0);
            assertThat(graded.getCellStyle().getLocked()).as("ô đã có điểm khóa").isTrue();
            assertThat(cell(wb, code(pGraded), h1).getCellStyle().getLocked()).as("không có trong nội dung thì khóa").isTrue();
            assertThat(cell(wb, code(pOne), h1).getCellStyle().getLocked()).as("người vắng vẫn chấm được").isFalse();
            assertThat(cell(wb, "0003", "Cấp bậc").getStringCellValue()).isEqualTo("Cả đơn vị");
            assertThat(cell(wb, "0003", h2).getCellStyle().getLocked()).isFalse();

            // Ba loại ô tô khác hẳn nhau: nhập (nền vàng), đã có điểm (nền xám), không tham gia (gạch chéo).
            XSSFCellStyle input = (XSSFCellStyle) cell(wb, code(pBoth), h1).getCellStyle();
            XSSFCellStyle done = (XSSFCellStyle) graded.getCellStyle();
            XSSFCellStyle none = (XSSFCellStyle) cell(wb, code(pGraded), h1).getCellStyle();
            assertThat(input.getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);
            assertThat(done.getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);
            assertThat(input.getFillForegroundXSSFColor().getARGBHex()).isNotEqualTo(done.getFillForegroundXSSFColor().getARGBHex());
            assertThat(none.getFillPattern()).isEqualTo(FillPatternType.THIN_FORWARD_DIAG);
            // Sọc xen kẽ (định dạng có điều kiện) không phủ cột điểm, để không đè màu các ô trên.
            int firstScore = headers(s).indexOf(h1);
            var cf = s.getSheetConditionalFormatting();
            for (int i = 0; i < cf.getNumConditionalFormattings(); i++) {
                for (var range : cf.getConditionalFormattingAt(i).getFormattingRanges()) {
                    assertThat(range.getLastColumn()).isLessThan(firstScore);
                }
            }
            // Dòng chú thích ngay trên tiêu đề cột.
            Row legend = s.getRow(headerRow(s) - 1);
            assertThat(legend.getCell(0).getStringCellValue()).contains("vàng");
            assertThat(((XSSFCellStyle) legend.getCell(0).getCellStyle()).getFillForegroundXSSFColor().getARGBHex())
                    .isEqualTo(input.getFillForegroundXSSFColor().getARGBHex());
        }
        // Chỉ Super Admin; kiểm quyền trước khi đọc file.
        template(admin, 403);
        assertThat(upload(admin, null, true).status()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ nhập

    @Test
    void nhapNhieuNoiDungMotLanGhiTatCaHoacKhong() throws Exception {
        byte[] bytes;
        try (Workbook wb = open(template(sa, 200))) {
            cell(wb, code(pBoth), h1).setCellValue(6.5);
            cell(wb, code(pBoth), h2).setCellValue(8);
            cell(wb, code(pGraded), h2).setCellValue(9);    // đổi điểm đã có: lỗi
            cell(wb, code(pGraded), h1).setCellValue(5);    // không có trong nội dung: lỗi
            cell(wb, code(pOne), h1).setCellValue("11");    // ngoài 0–10: lỗi
            bytes = save(wb);
        }
        Res dry = upload(sa, bytes, true);
        assertThat(dry.status()).as(String.valueOf(dry.body())).isEqualTo(200);
        assertThat(dry.body().get("errorCount").asInt()).as(String.valueOf(dry.body())).isEqualTo(3);
        assertThat(errorCells(dry.body())).containsExactlyInAnyOrder(h2, h1, h1);
        assertThat(dry.body().toString()).contains("đã có điểm 7").contains("không có trong nội dung");
        assertThat(upload(sa, bytes, false).body().get("written").asBoolean()).isFalse();
        assertThat(score(c1, pBoth)).as("có lỗi thì không ghi gì").isNull();

        try (Workbook wb = open(template(sa, 200))) {
            cell(wb, code(pBoth), h1).setCellValue(6.5);
            cell(wb, code(pBoth), h2).setCellValue(8);
            cell(wb, code(pOne), h1).setCellValue("4,9");
            cell(wb, "0003", h2).setCellValue(7.9);
            bytes = save(wb);                               // ô đã có điểm giữ nguyên 7: bỏ qua
        }
        Res ok = upload(sa, bytes, true);
        assertThat(ok.body().get("errorCount").asInt()).as(String.valueOf(ok.body())).isZero();
        assertThat(ok.body().get("counts").get("GRADE").asInt()).isEqualTo(4);
        Res write = upload(sa, bytes, false);
        assertThat(write.body().get("written").asBoolean()).as(String.valueOf(write.body())).isTrue();
        assertThat(score(c1, pBoth)).isEqualTo(65);
        assertThat(score(c2, pBoth)).isEqualTo(80);
        assertThat(score(c1, pOne)).isEqualTo(49);
        assertThat(score(c2, pGraded)).isEqualTo(70);
        assertThat(jdbc.queryForObject("""
                SELECT r.score_x10 FROM result r JOIN class_participant cp ON cp.id = r.participant_id
                JOIN app_user u ON u.id = cp.user_id WHERE cp.class_id = ? AND u.kind = 'UNIT'
                """, Integer.class, c2)).isEqualTo(79);
        assertThat(jdbc.queryForObject("SELECT absence_reason_id IS NULL FROM class_participant WHERE id = ?",
                Integer.class, partOne)).as("nhập điểm thì xóa lý do không kiểm tra").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_type = 'exam_period' AND entity_id = ?",
                Integer.class, AuditAction.RESULTS_IMPORTED, periodId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND after_json LIKE '%IMPORT%'"
                + " AND entity_id IN (SELECT r.id FROM result r JOIN class_participant cp ON cp.id = r.participant_id"
                + " JOIN exam_class ec ON ec.id = cp.class_id WHERE ec.period_id = ?)", Integer.class,
                AuditAction.RESULT_CREATED, periodId)).as("audit từng điểm như chấm tay").isEqualTo(4);

        // Nhập lại đúng file: mọi ô đã có điểm, giữ nguyên thì không có việc gì.
        Res again = upload(sa, bytes, true);
        assertThat(again.body().get("errorCount").asInt()).isZero();
        assertThat(again.body().get("rows").size()).isZero();
    }

    @Test
    void noiDungDongVaDotKhoaVanChamDuoc() throws Exception {
        ok(patch("/api/v1/periods/" + periodId + "/status"), sa, Map.of("status", "CLOSED"), 200);
        byte[] bytes;
        try (Workbook wb = open(template(sa, 200))) {
            cell(wb, code(pBoth), h1).setCellValue(5);
            bytes = save(wb);
        }
        assertThat(upload(sa, bytes, false).body().get("written").asBoolean()).isTrue();
        assertThat(score(c1, pBoth)).isEqualTo(50);
    }
}
