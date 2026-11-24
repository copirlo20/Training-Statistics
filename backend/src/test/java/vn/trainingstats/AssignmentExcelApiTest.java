package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import vn.trainingstats.audit.AuditAction;

/**
 * Phân công người học bằng Excel: file mẫu mỗi đơn vị một sheet theo phạm vi người tải, cột theo nội dung người đó
 * thao tác được; nhập so với tình trạng hiện tại, Super Admin và giám khảo thêm, gỡ ngay, Admin gửi đề nghị; ghi tất cả
 * hoặc không ghi gì.
 */
class AssignmentExcelApiTest extends M3TestBase {

    private static final String CODE_HEADER = "Mã định danh *";

    private MockHttpSession sa;
    private MockHttpSession admin0002;
    private MockHttpSession teacher;
    private MockHttpSession plain;
    private long periodId;
    private long c1;   // giám khảo là teacher
    private long c2;   // không có giám khảo
    private String h1;
    private String h2;
    private String tag;
    private long pTop;     // 0002
    private long pChild;   // 0002.0001
    private long pOther;   // 0003
    private long pGraded;  // 0002.0001, có điểm ở c2

    @BeforeEach
    void setUp() throws Exception {
        ensureUser("m8x-admin-0002", "ADMIN", "0002", PW, "ACTIVE");
        long teacherId = ensureUser("m8x-gv", "USER", "0008", PW, "ACTIVE");
        ensureUser("m8x-user", "USER", "0002.0002", PW, "ACTIVE");
        sa = login(SA_USERNAME, SA_PASSWORD);
        admin0002 = login("m8x-admin-0002", PW);
        teacher = login("m8x-gv", PW);
        plain = login("m8x-user", PW);

        periodId = period(sa);
        c1 = newClassIn("Bắn súng");
        c2 = newClassIn("Thể lực");
        setTeachers(sa, c1, teacherId);
        h1 = ok(get("/api/v1/classes/" + c1), sa, null, 200).body().get("name").asText() + " (20/11)";
        h2 = ok(get("/api/v1/classes/" + c2), sa, null, 200).body().get("name").asText() + " (20/11)";
        tag = "Xl" + System.nanoTime();
        pTop = person(sa, "0002", tag + "A");
        pChild = person(sa, "0002.0001", tag + "B");
        pOther = person(sa, "0003", tag + "C");
        pGraded = person(sa, "0002.0001", tag + "D");
        long part = addParticipant(sa, c2, pGraded);
        tx().executeWithoutResult(s -> jdbc.update("INSERT INTO result (participant_id, score_x10, graded_by) VALUES (?, 70, ?)",
                part, userIdOf(SA_USERNAME)));
    }

    private long newClassIn(String name) throws Exception {
        return ok(post("/api/v1/classes"), sa, Map.of("periodId", periodId, "name", uniq(name), "examDate", "2026-11-20"), 201).id();
    }

    // ------------------------------------------------------------------ tiện ích

    private byte[] template(MockHttpSession s, int expected) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/assignments/template").param("periodId", String.valueOf(periodId)).session(s))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expected);
        return r.getResponse().getContentAsByteArray();
    }

    private Res upload(MockHttpSession s, byte[] bytes, boolean dryRun) throws Exception {
        var b = multipart("/api/v1/assignments/import");
        if (bytes != null) {
            b = b.file(new MockMultipartFile("file", "phan-cong.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes));
        }
        MvcResult r = mvc.perform(b.param("periodId", String.valueOf(periodId)).param("dryRun", String.valueOf(dryRun))
                .session(s).with(csrf())).andReturn();
        String text = r.getResponse().getContentAsString();
        return new Res(r.getResponse().getStatus(), text.isEmpty() ? null : json.readTree(text));
    }

    private String code(long userId) {
        return jdbc.queryForObject("SELECT personal_code FROM app_user WHERE id = ?", String.class, userId);
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

    /** Ô của người mang mã {@code code} ở cột tiêu đề {@code header}, tìm trong mọi sheet. Null nếu không có. */
    private static Cell cell(Workbook wb, String code, String header) {
        for (Sheet s : wb) {
            int head = headerRow(s);
            if (head < 0) {
                continue;
            }
            List<String> hs = headers(s);
            int codeCol = hs.indexOf(CODE_HEADER);
            int col = hs.indexOf(header);
            for (int r = head + 1; r <= s.getLastRowNum(); r++) {
                Row row = s.getRow(r);
                Cell c = row == null ? null : row.getCell(codeCol);
                if (c != null && c.getCellType() == CellType.STRING && code.equals(c.getStringCellValue())) {
                    return col < 0 ? null : (row.getCell(col) != null ? row.getCell(col) : row.createCell(col));
                }
            }
        }
        return null;
    }

    private static String sheetOf(Workbook wb, String code) {
        for (Sheet s : wb) {
            int head = headerRow(s);
            if (head < 0) {
                continue;
            }
            int codeCol = headers(s).indexOf(CODE_HEADER);
            for (int r = head + 1; r <= s.getLastRowNum(); r++) {
                Row row = s.getRow(r);
                Cell c = row == null ? null : row.getCell(codeCol);
                if (c != null && c.getCellType() == CellType.STRING && code.equals(c.getStringCellValue())) {
                    return s.getSheetName();
                }
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

    private boolean inClass(long classId, long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM class_participant WHERE class_id = ? AND user_id = ? AND removed_at IS NULL",
                Integer.class, classId, userId) > 0;
    }

    private static List<String> errorCells(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.get("errors").forEach(e -> out.add(e.get("row").asInt() + "|" + e.get("column").asText()));
        return out;
    }

    // ------------------------------------------------------------------ file mẫu

    @Test
    void fileMauMoiDonViMotSheetTheoPhamViVaCotTheoQuyen() throws Exception {
        try (Workbook wb = open(template(sa, 200))) {
            assertThat(sheetOf(wb, code(pTop))).isEqualTo("Phòng Tham mưu");
            assertThat(sheetOf(wb, code(pChild))).as("đơn vị nhỏ nhất, không gộp vào đơn vị cha").isEqualTo("Ban Quân huấn");
            assertThat(sheetOf(wb, code(pOther))).isEqualTo("Phòng Chính trị");
            assertThat(wb.getSheetName(0)).as("sheet hướng dẫn đứng đầu như mọi file mẫu").isEqualTo("HuongDan");
            Sheet s = wb.getSheet("Ban Quân huấn");
            assertThat(headers(s)).containsSubsequence("STT", CODE_HEADER, "Họ tên", "Cấp bậc", "Đối tượng").contains(h1, h2);
            assertThat(cell(wb, code(pChild), "Đối tượng").getStringCellValue()).isEqualTo("SQ");
            Cell graded = cell(wb, code(pGraded), h2);
            assertThat(graded.getStringCellValue()).isEqualTo("x");
            assertThat(graded.getCellStyle().getLocked()).as("người đã có điểm bị khóa ô").isTrue();
            assertThat(cell(wb, code(pChild), h1).getCellStyle().getLocked()).as("ô phân công mở").isFalse();
            assertThat(s.getProtect()).isTrue();
            // Hai đơn vị trùng tên (Hải đội bộ) thì thêm mã để không trùng sheet.
            List<String> names = new ArrayList<>();
            wb.forEach(sh -> names.add(sh.getSheetName()));
            assertThat(names).doesNotHaveDuplicates().allMatch(n -> n.length() <= 31);
        }
        // Admin: chỉ đơn vị trong nhánh; mọi nội dung đang mở (gửi đề nghị).
        try (Workbook wb = open(template(admin0002, 200))) {
            assertThat(sheetOf(wb, code(pChild))).isEqualTo("Ban Quân huấn");
            assertThat(sheetOf(wb, code(pOther))).as("ngoài nhánh").isNull();
            assertThat(wb.getSheet("Phòng Chính trị")).isNull();
            assertThat(headers(wb.getSheet("Ban Quân huấn"))).contains(h1, h2);
        }
        // Giám khảo: toàn bộ danh sách, chỉ cột nội dung mình được gán.
        try (Workbook wb = open(template(teacher, 200))) {
            assertThat(sheetOf(wb, code(pOther))).isEqualTo("Phòng Chính trị");
            assertThat(headers(wb.getSheet("Phòng Chính trị"))).contains(h1).doesNotContain(h2);
        }
        template(plain, 404);
        assertThat(upload(plain, null, true).status()).as("kiểm quyền trước khi đọc file").isEqualTo(404);
    }

    // ------------------------------------------------------------------ nhập

    @Test
    void superAdminThemGoNgayVaGhiTatCaHoacKhong() throws Exception {
        byte[] bytes;
        try (Workbook wb = open(template(sa, 200))) {
            cell(wb, code(pChild), h1).setCellValue("x");
            cell(wb, code(pTop), h2).setCellValue("X");
            cell(wb, code(pGraded), h2).setBlank();          // gỡ người đã có điểm: lỗi
            cell(wb, code(pOther), h1).setCellValue("có");   // giá trị lạ: lỗi
            bytes = save(wb);
        }
        Res dry = upload(sa, bytes, true);
        assertThat(dry.status()).as(String.valueOf(dry.body())).isEqualTo(200);
        assertThat(dry.body().get("errorCount").asInt()).isEqualTo(2);
        assertThat(dry.body().toString()).contains("đã có điểm").contains("Chỉ ghi x");
        Res rejected = upload(sa, bytes, false);
        assertThat(rejected.body().get("written").asBoolean()).isFalse();
        assertThat(inClass(c1, pChild)).as("có lỗi thì không ghi gì").isFalse();

        try (Workbook wb = open(template(sa, 200))) {
            cell(wb, code(pChild), h1).setCellValue("x");
            cell(wb, code(pTop), h2).setCellValue("X");
            bytes = save(wb);
        }
        Res ok = upload(sa, bytes, true);
        assertThat(ok.body().get("errorCount").asInt()).as(String.valueOf(ok.body())).isZero();
        assertThat(ok.body().get("counts").get("ADD").asInt()).isEqualTo(2);
        assertThat(upload(sa, bytes, false).body().get("written").asBoolean()).isTrue();
        assertThat(inClass(c1, pChild)).isTrue();
        assertThat(inClass(c2, pTop)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_id = ?", Integer.class,
                AuditAction.ASSIGNMENTS_IMPORTED, periodId)).isEqualTo(1);

        // Xóa x là gỡ; nhập lại đúng file vừa tải thì không có thay đổi.
        try (Workbook wb = open(template(sa, 200))) {
            assertThat(cell(wb, code(pChild), h1).getStringCellValue()).isEqualTo("x");
            byte[] same = save(wb);
            Res none = upload(sa, same, true);
            assertThat(none.body().get("errorCount").asInt()).isZero();
            assertThat(none.body().get("rows").size()).isZero();
            cell(wb, code(pChild), h1).setBlank();
            bytes = save(wb);
        }
        Res removed = upload(sa, bytes, false);
        assertThat(removed.body().get("counts").get("REMOVE").asInt()).isEqualTo(1);
        assertThat(inClass(c1, pChild)).isFalse();
    }

    @Test
    void adminGuiDeNghiChiTrongNhanhVaGiamKhaoChiNoiDungMinh() throws Exception {
        byte[] bytes;
        try (Workbook wb = open(template(admin0002, 200))) {
            cell(wb, code(pChild), h1).setCellValue("x");
            cell(wb, code(pTop), h2).setCellValue("x");
            // Thêm một dòng người ngoài nhánh vào sheet: không khớp được, báo lỗi.
            Sheet s = wb.getSheet("Ban Quân huấn");
            Row extra = s.createRow(s.getLastRowNum() + 1);
            extra.createCell(headers(s).indexOf(CODE_HEADER)).setCellValue(code(pOther));
            extra.createCell(headers(s).indexOf(h1)).setCellValue("x");
            bytes = save(wb);
        }
        Res dry = upload(admin0002, bytes, true);
        assertThat(errorCells(dry.body())).hasSize(1);
        assertThat(dry.body().toString()).contains("Không có người mã").doesNotContain("Trần Văn " + tag + "C");

        try (Workbook wb = open(template(admin0002, 200))) {
            cell(wb, code(pChild), h1).setCellValue("x");
            cell(wb, code(pTop), h2).setCellValue("x");
            bytes = save(wb);
        }
        int notesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class);
        Res write = upload(admin0002, bytes, false);
        assertThat(write.body().get("written").asBoolean()).as(String.valueOf(write.body())).isTrue();
        assertThat(write.body().get("counts").get("REQUEST_ADD").asInt()).isEqualTo(2);
        assertThat(inClass(c1, pChild)).as("Admin chỉ gửi đề nghị").isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM class_request WHERE status = 'PENDING' AND type = 'ADD'"
                + " AND ((class_id = ? AND user_id = ?) OR (class_id = ? AND user_id = ?))", Integer.class, c1, pChild, c2, pTop))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class))
                .as("một thông báo gộp mỗi nội dung, không mỗi đề nghị").isGreaterThan(notesBefore);

        // File mới: ô đang chờ duyệt bị khóa, ghi chữ, và bị bỏ qua khi nhập.
        try (Workbook wb = open(template(admin0002, 200))) {
            Cell pending = cell(wb, code(pChild), h1);
            assertThat(pending.getStringCellValue()).isEqualTo("Chờ duyệt thêm");
            assertThat(pending.getCellStyle().getLocked()).isTrue();
            Res none = upload(admin0002, save(wb), true);
            assertThat(none.body().get("errorCount").asInt()).isZero();
            assertThat(none.body().get("rows").size()).isZero();
        }

        // Giám khảo thêm ngay vào nội dung mình; cột nội dung khác không có trong file của họ.
        try (Workbook wb = open(template(teacher, 200))) {
            cell(wb, code(pOther), h1).setCellValue("x");
            assertThat(cell(wb, code(pOther), h2)).isNull();
            bytes = save(wb);
        }
        assertThat(upload(teacher, bytes, false).body().get("written").asBoolean()).isTrue();
        assertThat(inClass(c1, pOther)).isTrue();
    }

    @Test
    void dotDaKhoaThiKhongCoFileMau() throws Exception {
        ok(patch("/api/v1/periods/" + periodId + "/status"),
                sa, Map.of("status", "CLOSED"), 200);
        assertThat(new String(template(sa, 409), StandardCharsets.UTF_8)).contains("NO_CONTENT");
    }
}
