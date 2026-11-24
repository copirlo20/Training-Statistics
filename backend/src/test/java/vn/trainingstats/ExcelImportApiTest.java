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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import vn.trainingstats.audit.AuditAction;

/**
 * M5: nhập người dùng, lớp, điểm bằng Excel qua API thật (file .xlsx dựng bằng POI trong test).
 * Cây dùng trong test: 0002 có con 0002.0001; 0003, 0004 là nhánh khác.
 */
class ExcelImportApiTest extends M3TestBase {

    private static final String USER_HEADERS = "Mã định danh|Họ đệm *|Tên *|Đơn vị *|Cấp bậc|Chức vụ|Chuyên ngành|Ngày sinh|Số điện thoại|Ghi chú";

    private MockHttpSession sa;
    private MockHttpSession admin0002;
    private MockHttpSession teacher;
    private MockHttpSession plainUser;
    private long teacherId;
    private String rankName;

    @BeforeEach
    void setUp() throws Exception {
        ensureUser("m5-admin-0002", "ADMIN", "0002", PW, "ACTIVE");
        teacherId = ensureUser("m5-gv", "USER", "0004", PW, "ACTIVE");
        ensureUser("m5-user", "USER", "0002.0001", PW, "ACTIVE");
        sa = login(SA_USERNAME, SA_PASSWORD);
        admin0002 = login("m5-admin-0002", PW);
        teacher = login("m5-gv", PW);
        plainUser = login("m5-user", PW);
        rankName = jdbc.queryForObject("SELECT name FROM rank WHERE active = 1 ORDER BY sort_order LIMIT 1", String.class);
    }

    // ------------------------------------------------------------------ tiện ích

    /** File .xlsx: mỗi sheet là tiêu đề (cách nhau bởi |) và các dòng. */
    static byte[] xlsx(Map<String, List<List<Object>>> sheets) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (Map.Entry<String, List<List<Object>>> e : sheets.entrySet()) {
                Sheet s = wb.createSheet(e.getKey());
                int r = 0;
                for (List<Object> values : e.getValue()) {
                    Row row = s.createRow(r++);
                    for (int c = 0; c < values.size(); c++) {
                        Object v = values.get(c);
                        if (v instanceof Number n) {
                            row.createCell(c).setCellValue(n.doubleValue());
                        } else if (v != null) {
                            row.createCell(c).setCellValue(v.toString());
                        }
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    static List<Object> header(String pipe) {
        return new ArrayList<>(Arrays.asList((Object[]) pipe.split("\\|")));
    }

    static List<Object> row(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    /**
     * Thêm cột "Đối tượng *" (bắt buộc từ 2026-11-20) vào cuối sheet người dùng: dòng tiêu đề thêm tên cột, các dòng dữ
     * liệu đệm cho đủ cột rồi thêm {@code category}. Máy chủ tìm cột theo tên nên vị trí cột không ảnh hưởng.
     */
    static List<List<Object>> withCategory(List<List<Object>> sheet, String category) {
        List<List<Object>> out = new ArrayList<>();
        int width = sheet.get(0).size();
        for (int i = 0; i < sheet.size(); i++) {
            List<Object> r = new ArrayList<>(sheet.get(i));
            while (r.size() < width) {
                r.add(null);
            }
            r.add(i == 0 ? "Đối tượng *" : category);
            out.add(r);
        }
        return out;
    }

    static List<List<Object>> withCategory(List<List<Object>> sheet) {
        return withCategory(sheet, "SQ");
    }

    Res upload(String url, MockHttpSession s, byte[] bytes, String fileName) throws Exception {
        MvcResult r = mvc.perform(multipart(url).file(new MockMultipartFile("file", fileName,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes)).session(s).with(csrf()))
                .andReturn();
        String text = r.getResponse().getContentAsString();
        return new Res(r.getResponse().getStatus(), text.isEmpty() ? null : json.readTree(text));
    }

    Res upload(String url, MockHttpSession s, byte[] bytes) throws Exception {
        return upload(url, s, bytes, "nhap.xlsx");
    }

    byte[] download(String url, MockHttpSession s, int expected) throws Exception {
        MvcResult r = mvc.perform(get(url).session(s)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(expected);
        return r.getResponse().getContentAsByteArray();
    }

    /** Chỉ số (0) của dòng có ô đầu tiên bằng {@code firstHeader}. */
    static int headerRow(Sheet s, String firstHeader) {
        for (int r = 0; r <= 20; r++) {
            Row row = s.getRow(r);
            if (row != null && row.getCell(0) != null && row.getCell(0).getCellType() == CellType.STRING
                    && firstHeader.equals(row.getCell(0).getStringCellValue())) {
                return r;
            }
        }
        throw new AssertionError("Không thấy dòng tiêu đề " + firstHeader);
    }

    static byte[] save(Workbook wb) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        }
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    /** Lỗi trong báo cáo, dạng "dòng|cột". */
    static List<String> errorCells(JsonNode report) {
        List<String> out = new ArrayList<>();
        report.get("errors").forEach(e -> out.add(e.get("row").asInt() + "|" + e.get("column").asText()));
        return out;
    }

    // ------------------------------------------------------------------ M5.1 hạ tầng

    @Test
    void fileMauNguoiDungCoRangBuocVaBoCuc() throws Exception {
        byte[] bytes = download("/api/v1/users/import-template", admin0002, 200);
        download("/api/v1/users/import-template", plainUser, 403);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet s = wb.getSheet("NguoiDung");
            int head = headerRow(s, "Mã định danh");
            assertThat(head).as("có khối tiêu đề và thông tin phía trên bảng").isGreaterThan(2);
            assertThat(s.getRow(1).getCell(0).getStringCellValue()).isEqualTo("DANH SÁCH NGƯỜI DÙNG NHẬP VÀO HỆ THỐNG");
            assertThat(s.getRow(head + 1).getCell(0).getCellStyle().getDataFormatString()).as("cột mã định dạng Text (R8)").isEqualTo("@");
            assertThat(s.getRow(head + 1).getCell(11).getCellStyle().getDataFormatString()).as("số điện thoại định dạng Text").isEqualTo("@");
            assertThat(s.getRow(head + 1).getCell(10).getCellStyle().getDataFormatString()).as("ngày sinh là ngày thật").isEqualTo("dd/mm/yyyy");
            assertThat(s.getRow(head).getCell(7).getStringCellValue()).isEqualTo("Chuyên ngành 1");
            assertThat(s.getRow(head).getCell(4).getStringCellValue()).isEqualTo("Đối tượng *");
            assertThat(s.getColumnStyle(0).getDataFormatString()).as("dòng ngoài vùng kẻ sẵn vẫn Text").isEqualTo("@");
            var validations = s.getDataValidations();
            assertThat(validations).as("mỗi cột một ràng buộc").hasSize(15);
            long listCount = validations.stream().filter(v -> v.getValidationConstraint().getValidationType()
                    == DataValidationConstraint.ValidationType.LIST).count();
            assertThat(listCount).as("đơn vị, đối tượng, cấp bậc, chức vụ, 3 cột chuyên ngành, quyền chọn từ danh sách").isEqualTo(8);
            assertThat(validations).as("Excel từ chối giá trị sai").allMatch(v -> v.getErrorStyle() == DataValidation.ErrorStyle.STOP)
                    .allMatch(v -> !v.getShowPromptBox() && v.getShowErrorBox());
            assertThat(validations).anyMatch(v -> v.getValidationConstraint().getFormula1() != null
                    && v.getValidationConstraint().getFormula1().contains("COUNTIF")).as("mã không trùng trong file");
            assertThat(s.getPaneInformation().isFreezePane()).isTrue();
            assertThat(wb.getSheetName(0)).as("sheet hướng dẫn đứng đầu").isEqualTo("HuongDan");
            assertThat(wb.getActiveSheetIndex()).as("mở file ra ở sheet hướng dẫn").isZero();
            assertThat(s.getRepeatingRows()).as("dòng tiêu đề in lặp vẫn gắn đúng sheet dữ liệu sau khi đổi thứ tự")
                    .isNotNull();
            assertThat(s.getRepeatingRows().getFirstRow()).isEqualTo(head);
            Sheet lists = wb.getSheet("DanhMuc");
            assertThat(wb.isSheetVeryHidden(wb.getSheetIndex(lists))).isTrue();
            assertThat(lists.getRow(0).getLastCellNum()).as("ba cột chuyên ngành dùng chung một danh sách").isEqualTo((short) 6);
            assertThat(s.getRow(head).getCell(12).getStringCellValue()).isEqualTo("Tài khoản");
            assertThat(s.getRow(head).getCell(13).getStringCellValue()).isEqualTo("Quyền");
            List<String> values = new ArrayList<>();
            lists.forEach(r -> r.forEach(c -> values.add(c.getStringCellValue())));
            assertThat(values).as("cấp bậc theo tên (R7)").contains(rankName);
            assertThat(values).as("Admin 0002 chỉ thấy đơn vị trong nhánh").anyMatch(v -> v.startsWith("0002.0001 - "))
                    .noneMatch(v -> v.startsWith("0003"));
        }
    }

    /** Điền thẳng vào file mẫu tải về (giữ khối tiêu đề, kiểu ô ngày, Text) rồi nhập: file mẫu phải đọc lại được. */
    @Test
    void dienVaoChinhFileMauRoiNhap() throws Exception {
        String stamp = String.valueOf(System.nanoTime());
        byte[] usersFile;
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(download("/api/v1/users/import-template", admin0002, 200)))) {
            Sheet s = wb.getSheet("NguoiDung");
            Sheet lists = wb.getSheet("DanhMuc");
            Row r = s.getRow(headerRow(s, "Mã định danh") + 1);
            r.getCell(1).setCellValue("Mẫu Văn");
            r.getCell(2).setCellValue("Điền " + stamp);
            r.getCell(3).setCellValue(lists.getRow(0).getCell(0).getStringCellValue());
            r.getCell(4).setCellValue("HSQ-CS");
            r.getCell(5).setCellValue(lists.getRow(0).getCell(2).getStringCellValue());
            r.getCell(7).setCellValue(lists.getRow(0).getCell(4).getStringCellValue());
            r.getCell(8).setCellValue(lists.getRow(1).getCell(4).getStringCellValue());
            r.getCell(10).setCellValue(LocalDate.of(1995, 9, 5));
            r.getCell(11).setCellValue("0912345678");
            // Đổi tên sheet dữ liệu: máy chủ đọc sheet dữ liệu đầu tiên, không đọc nhầm sheet hướng dẫn đứng trước.
            wb.setSheetName(wb.getSheetIndex(s), "Nhap");
            usersFile = save(wb);
        }
        Res users = upload("/api/v1/users/import?dryRun=false", admin0002, usersFile);
        assertThat(users.body().get("written").asBoolean()).as(String.valueOf(users.body())).isTrue();
        assertThat(count("SELECT COUNT(*) FROM app_user WHERE first_name = ? AND birth_date = '1995-09-05' AND phone = '0912345678'",
                "Điền " + stamp)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_specialty us JOIN app_user u ON u.id = us.user_id WHERE u.first_name = ?",
                "Điền " + stamp)).as("hai chuyên ngành chọn ở hai cột").isEqualTo(2);

        long period = period(sa);
        String periodName = jdbc.queryForObject("SELECT name FROM exam_period WHERE id = ?", String.class, period);
        String code = jdbc.queryForObject("SELECT personal_code FROM app_user WHERE id = ?", String.class, person(sa, "0002.0001", "Mẫu"));
        byte[] classFile;
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(download("/api/v1/classes/import-template", sa, 200)))) {
            assertThat(wb.getSheetName(0)).as("sheet hướng dẫn đứng đầu").isEqualTo("HuongDan");
            Sheet content = wb.getSheet("NoiDung");
            Row r = content.getRow(headerRow(content, "Tên nội dung *") + 1);
            Row header = content.getRow(headerRow(content, "Tên nội dung *"));
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("Đợt kiểm tra *");
            assertThat(header.getCell(2).getStringCellValue()).as("không còn cột môn").isEqualTo("Ngày kiểm tra");
            r.getCell(0).setCellValue("Nội dung mẫu " + stamp);
            r.getCell(1).setCellValue(periodName);
            r.getCell(2).setCellValue(LocalDate.of(2026, 11, 25));
            Sheet people = wb.getSheet("NguoiHoc");
            assertThat(people.getDataValidations()).as("tên nội dung chọn từ sheet NoiDung")
                    .anyMatch(v -> v.getValidationConstraint().getFormula1() != null && v.getValidationConstraint().getFormula1().contains("NoiDung"));
            Row p = people.getRow(headerRow(people, "Tên nội dung *") + 1);
            p.getCell(0).setCellValue("Nội dung mẫu " + stamp);
            // Giá trị của ô chọn có dạng "mã - họ tên (đơn vị)".
            p.getCell(1).setCellValue(code + " - Trần Văn Mẫu (Ban Quân huấn)");
            List<String> options = new ArrayList<>();
            wb.getSheet("DanhMuc").forEach(row -> row.forEach(c -> options.add(c.getStringCellValue())));
            assertThat(options).as("ô chọn người học lấy từ danh sách người dùng").anyMatch(v -> v.startsWith(code + " - "));
            classFile = save(wb);
        }
        Res classes = upload("/api/v1/classes/import?dryRun=false", sa, classFile);
        assertThat(classes.body().get("written").asBoolean()).as(String.valueOf(classes.body())).isTrue();
        long classId = jdbc.queryForObject("SELECT id FROM exam_class WHERE name = ?", Long.class, "Nội dung mẫu " + stamp);
        assertThat(jdbc.queryForObject("SELECT exam_date FROM exam_class WHERE id = ?", String.class, classId)).isEqualTo("2026-11-25");

        byte[] resultFile;
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(download("/api/v1/classes/" + classId + "/results/import-template", sa, 200)))) {
            assertThat(wb.getSheetName(0)).as("sheet hướng dẫn đứng đầu").isEqualTo("HuongDan");
            Sheet d = wb.getSheet("Diem");
            Row r = d.getRow(headerRow(d, "STT") + 1);
            assertThat(r.getCell(1).getStringCellValue()).isEqualTo(code);
            r.getCell(6).setCellValue(8.5);
            resultFile = save(wb);
        }
        Res results = upload("/api/v1/classes/" + classId + "/results/import?dryRun=false", sa, resultFile);
        assertThat(results.body().get("written").asBoolean()).as(String.valueOf(results.body())).isTrue();
        assertThat(count("""
                SELECT COUNT(*) FROM result r JOIN class_participant cp ON cp.id = r.participant_id
                WHERE cp.class_id = ? AND r.score_x10 = 85""", classId)).isEqualTo(1);
    }

    @Test
    void gioiHanFileVaDinhDang() throws Exception {
        String url = "/api/v1/users/import?dryRun=true";
        Res none = call(post(url), admin0002, null);
        assertThat(none.status()).isEqualTo(400);
        assertThat(none.body().get("code").asText()).isEqualTo("FILE_REQUIRED");
        assertThat(upload(url, admin0002, "a,b,c".getBytes(), "nhap.csv").body().get("code").asText()).isEqualTo("INVALID_FILE");
        assertThat(upload(url, admin0002, "khong phai excel".getBytes()).body().get("code").asText()).isEqualTo("INVALID_FILE");
        assertThat(upload(url, admin0002, new byte[5 * 1024 * 1024 + 1]).body().get("code").asText()).isEqualTo("FILE_TOO_LARGE");

        byte[] missing = xlsx(Map.of("NguoiDung", List.of(header("Họ đệm *|Tên *"), row("A", "B"))));
        Res r = upload(url, admin0002, missing);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("code").asText()).isEqualTo("INVALID_TEMPLATE");
        assertThat(r.body().get("message").asText()).contains("Đơn vị");

        List<List<Object>> many = new ArrayList<>();
        many.add(header(USER_HEADERS));
        for (int i = 0; i < 5001; i++) {
            many.add(row(null, "Nguyễn", "Thừa" + i, "0002"));
        }
        Res tooMany = upload(url, admin0002, xlsx(Map.of("NguoiDung", withCategory(many))));
        assertThat(tooMany.status()).isEqualTo(400);
        assertThat(tooMany.body().get("code").asText()).isEqualTo("TOO_MANY_ROWS");
    }

    // ------------------------------------------------------------------ M5.2 người dùng

    @Test
    void nhap1000NguoiDungDungVaKhongGhiNuaChung() throws Exception {
        String stamp = String.valueOf(System.nanoTime());
        List<List<Object>> good = new ArrayList<>();
        good.add(header(USER_HEADERS));
        for (int i = 0; i < 1000; i++) {
            String code = i % 2 == 0 ? null : "M5-" + stamp + "-" + i;
            String unit = i % 3 == 0 ? "0002" : "0002.0001 - Tên đơn vị bất kỳ";
            good.add(row(code, "Nguyễn Văn", "T" + stamp + "-" + i, unit, rankName, null, null, "05/09/1995", "0912345678", null));
        }
        int before = count("SELECT COUNT(*) FROM app_user");
        long started = System.currentTimeMillis();
        Res dry = upload("/api/v1/users/import?dryRun=true", admin0002, xlsx(Map.of("NguoiDung", withCategory(good))));
        assertThat(dry.status()).as(String.valueOf(dry.body())).isEqualTo(200);
        assertThat(dry.body().get("errorCount").asInt()).as(String.valueOf(dry.body().get("errors"))).isZero();
        assertThat(dry.body().get("counts").get("CREATE").asInt()).isEqualTo(1000);
        assertThat(dry.body().get("written").asBoolean()).isFalse();
        assertThat(count("SELECT COUNT(*) FROM app_user")).as("dryRun không ghi").isEqualTo(before);

        Res write = upload("/api/v1/users/import?dryRun=false", admin0002, xlsx(Map.of("NguoiDung", withCategory(good))));
        assertThat(write.body().get("written").asBoolean()).as(String.valueOf(write.body())).isTrue();
        assertThat(System.currentTimeMillis() - started).as("1000 dòng, kiểm và ghi").isLessThan(60_000);
        assertThat(count("SELECT COUNT(*) FROM app_user")).isEqualTo(before + 1000);
        assertThat(count("SELECT COUNT(*) FROM app_user WHERE personal_code LIKE ?", "M5-" + stamp + "-%")).isEqualTo(500);
        assertThat(count("SELECT COUNT(*) FROM app_user WHERE first_name LIKE ? AND personal_code GLOB 'ND[0-9][0-9][0-9][0-9][0-9][0-9]*'",
                "T" + stamp + "-%")).as("mã tự cấp").isEqualTo(500);
        assertThat(count("SELECT COUNT(DISTINCT personal_code) FROM app_user")).isEqualTo(count("SELECT COUNT(*) FROM app_user WHERE personal_code IS NOT NULL"));
        assertThat(count("SELECT COUNT(*) FROM app_user WHERE personal_code = ? AND birth_date = '1995-09-05' AND phone = '0912345678'",
                "M5-" + stamp + "-1")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action = ? AND after_json LIKE ?", AuditAction.USERS_IMPORTED,
                "%M5-" + stamp + "-1\"%")).isEqualTo(1);

        // Cùng 1000 dòng nhưng có dòng sai, trùng, ngoài phạm vi: báo đúng dòng, không ghi dòng nào.
        long outsider = person(sa, "0003", "Ngoài");
        String outsiderCode = jdbc.queryForObject("SELECT personal_code FROM app_user WHERE id = ?", String.class, outsider);
        String stamp2 = String.valueOf(System.nanoTime());
        List<List<Object>> bad = new ArrayList<>();
        bad.add(header(USER_HEADERS));
        for (int i = 0; i < 1000; i++) {
            bad.add(row("M5B-" + stamp2 + "-" + i, "Lê Văn", "Lỗi " + i, "0002.0001", rankName));
        }
        // Dòng Excel = chỉ số + 2 (dòng tiêu đề là 1).
        bad.get(9).set(4, "Cấp bậc không có");
        bad.get(20).set(0, "M5B-" + stamp2 + "-18");
        bad.get(30).set(3, "0003");
        bad.get(40).set(0, outsiderCode);
        bad.get(50).set(2, null);
        bad.add(row("M5B-" + stamp2 + "-x1", "Lê Văn", "Ngày sai", "0002.0001", null, null, null, "31/02/2000"));
        bad.add(row("M5B-" + stamp2 + "-x2", "Lê Văn", "Điện thoại sai", "0002.0001", null, null, null, null, "abc"));
        bad.add(row("M5B-" + stamp2 + "-x3", "Lê Văn", "Đơn vị lạ", "9999"));
        int beforeBad = count("SELECT COUNT(*) FROM app_user");
        Res rejected = upload("/api/v1/users/import?dryRun=false", admin0002, xlsx(Map.of("NguoiDung", withCategory(bad))));
        assertThat(rejected.status()).isEqualTo(200);
        assertThat(rejected.body().get("written").asBoolean()).isFalse();
        assertThat(errorCells(rejected.body())).containsExactlyInAnyOrder(
                "10|Cấp bậc", "21|Mã định danh", "31|Đơn vị", "41|Mã định danh", "51|Tên",
                "1002|Ngày sinh", "1003|Số điện thoại", "1004|Đơn vị");
        assertThat(rejected.body().toString()).as("không lộ họ tên người ngoài phạm vi").doesNotContain("Trần Văn Ngoài");
        assertThat(count("SELECT COUNT(*) FROM app_user")).as("có lỗi thì không ghi gì").isEqualTo(beforeBad);
    }

    @Test
    void capNhatTheoMaHienNguoiDuocKhopVaGiuOTrong() throws Exception {
        long id = person(sa, "0002.0001", "Cũ");
        String code = jdbc.queryForObject("SELECT personal_code FROM app_user WHERE id = ?", String.class, id);
        // Ô Đối tượng để trống: người đã có đối tượng thì giữ nguyên.
        byte[] file = xlsx(Map.of("NguoiDung", withCategory(List.of(header(USER_HEADERS),
                row(code, "Trần Văn", "Mới", "0002", rankName, null, null, null, null, null)), null)));
        Res dry = upload("/api/v1/users/import?dryRun=true", admin0002, file);
        JsonNode item = dry.body().get("rows").get(0);
        assertThat(item.get("action").asText()).isEqualTo("UPDATE");
        assertThat(item.get("detail").asText()).as("R8: hiện họ tên người được khớp").contains("Trần Văn Cũ").contains("chuyển sang");

        Res write = upload("/api/v1/users/import?dryRun=false", admin0002, file);
        assertThat(write.body().get("written").asBoolean()).isTrue();
        Map<String, Object> u = jdbc.queryForMap("""
                SELECT u.first_name, u.phone, u.birth_date, un.code AS unit_code, r.name AS rank_name
                FROM app_user u JOIN unit un ON un.id = u.unit_id LEFT JOIN rank r ON r.id = u.rank_id WHERE u.id = ?
                """, id);
        assertThat(u.get("first_name")).isEqualTo("Mới");
        assertThat(u.get("unit_code")).isEqualTo("0002");
        assertThat(u.get("rank_name")).isEqualTo(rankName);
        assertThat(u.get("phone")).as("ô trống giữ giá trị cũ").isEqualTo("0912345678");
        assertThat(jdbc.queryForObject("SELECT category FROM app_user WHERE id = ?", String.class, id))
                .as("ô Đối tượng trống giữ đối tượng cũ").isEqualTo("SQ");
        assertThat(u.get("birth_date")).isEqualTo("1995-05-05");
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_id = ?", AuditAction.USER_MOVED, id)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM app_user WHERE id = ? AND search_text LIKE '%moi%'", id)).as("cập nhật cột tìm kiếm").isEqualTo(1);
    }

    @Test
    void doiTuongBatBuocVoiNguoiMoiVaNhanTenDayDu() throws Exception {
        String stamp = String.valueOf(System.nanoTime());
        String h = "Mã định danh|Họ đệm *|Tên *|Đơn vị *|Đối tượng *";
        byte[] bad = xlsx(Map.of("NguoiDung", List.of(header(h),
                row(null, "Lê Văn", "Thiếu " + stamp, "0002.0001", null),
                row(null, "Lê Văn", "Sai " + stamp, "0002.0001", "Chiến sĩ thi đua"))));
        Res rejected = upload("/api/v1/users/import?dryRun=true", admin0002, bad);
        assertThat(errorCells(rejected.body())).as(String.valueOf(rejected.body()))
                .containsExactlyInAnyOrder("2|Đối tượng", "3|Đối tượng");

        // Thiếu hẳn cột: báo sai mẫu.
        Res noColumn = upload("/api/v1/users/import?dryRun=true", admin0002, xlsx(Map.of("NguoiDung",
                List.of(header("Mã định danh|Họ đệm *|Tên *|Đơn vị *"), row(null, "A", "B", "0002")))));
        assertThat(noColumn.body().get("code").asText()).isEqualTo("INVALID_TEMPLATE");
        assertThat(noColumn.body().get("message").asText()).contains("Đối tượng");

        byte[] good = xlsx(Map.of("NguoiDung", List.of(header(h),
                row(null, "Lê Văn", "SQ " + stamp, "0002.0001", "sq"),
                row(null, "Lê Văn", "QNCN " + stamp, "0002.0001", "Quân nhân chuyên nghiệp"),
                row(null, "Lê Văn", "CS " + stamp, "0002.0001", "HSQ-CS"),
                row(null, "Lê Văn", "CS2 " + stamp, "0002.0001", "hạ sĩ quan - chiến sĩ"))));
        Res write = upload("/api/v1/users/import?dryRun=false", admin0002, good);
        assertThat(write.body().get("written").asBoolean()).as(String.valueOf(write.body())).isTrue();
        assertThat(jdbc.queryForList("SELECT category FROM app_user WHERE first_name LIKE ? ORDER BY id", String.class,
                "% " + stamp)).containsExactly("SQ", "QNCN", "HSQ-CS", "HSQ-CS");
    }

    @Test
    void capTaiKhoanVaQuyenBangExcel() throws Exception {
        String stamp = String.valueOf(System.nanoTime());
        String headers = "Mã định danh|Họ đệm *|Tên *|Đơn vị *|Tài khoản|Quyền";
        long existing = person(sa, "0002.0001", "Có sẵn " + stamp);
        String existingCode = jdbc.queryForObject("SELECT personal_code FROM app_user WHERE id = ?", String.class, existing);

        byte[] good = xlsx(Map.of("NguoiDung", withCategory(List.of(header(headers),
                row(null, "Lê Văn", "Quản trị " + stamp, "0002.0001", "m5a-" + stamp, "Admin"),
                row(null, "Lê Văn", "Thường " + stamp, "0002.0001", "m5u-" + stamp, null),
                row(existingCode, "Trần Văn", "Có sẵn " + stamp, "0002.0001", "m5e-" + stamp, "Admin"),
                row(null, "Lê Văn", "Không TK " + stamp, "0002", null, "Người dùng")))));
        Res dry = upload("/api/v1/users/import?dryRun=true", admin0002, good);
        assertThat(dry.body().get("errorCount").asInt()).as(String.valueOf(dry.body())).isZero();
        assertThat(dry.body().get("rows").get(0).get("detail").asText()).contains("cấp tài khoản m5a-").contains("quyền Admin");
        assertThat(dry.body().get("rows").get(2).get("detail").asText()).contains("Khớp với").contains("quyền Admin");

        Res write = upload("/api/v1/users/import?dryRun=false", admin0002, good);
        assertThat(write.body().get("written").asBoolean()).as(String.valueOf(write.body())).isTrue();
        assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE username = ?", String.class, "m5a-" + stamp)).isEqualTo("ADMIN");
        assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE username = ?", String.class, "m5u-" + stamp)).isEqualTo("USER");
        assertThat(jdbc.queryForObject("SELECT role || '|' || username FROM app_user WHERE id = ?", String.class, existing))
                .isEqualTo("ADMIN|m5e-" + stamp);
        assertThat(count("SELECT COUNT(*) FROM app_user WHERE first_name = ? AND username IS NULL AND role = 'USER'",
                "Không TK " + stamp)).isEqualTo(1);
        for (String u : List.of("m5a-", "m5u-", "m5e-")) {
            assertThat(loginResult(u + stamp, "123456789").getResponse().getStatus()).as("mật khẩu mặc định " + u).isEqualTo(200);
            assertThat(count("SELECT must_change_password FROM app_user WHERE username = ?", u + stamp))
                    .as("tài khoản cấp bằng Excel bị bắt đổi mật khẩu " + u).isEqualTo(1);
        }
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_id = ?", AuditAction.USER_ROLE_CHANGED, existing))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action = ? AND after_json LIKE ? AND after_json LIKE '%\"accounts\":3%'",
                AuditAction.USERS_IMPORTED, "%" + existingCode + "%")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE after_json LIKE '%123456789%' OR after_json LIKE '%$2%'"))
                .as("không ghi mật khẩu, hash vào nhật ký").isZero();

        // Lỗi báo ngay ở bước kiểm tra, không ghi dòng nào.
        String myCode = jdbc.queryForObject("SELECT personal_code FROM app_user WHERE username = 'm5-admin-0002'", String.class);
        byte[] bad = xlsx(Map.of("NguoiDung", withCategory(List.of(header(headers),
                row(null, "A", "Admin thiếu TK", "0002.0001", null, "Admin"),
                row(null, "A", "Trùng 1", "0002.0001", "m5d-" + stamp, null),
                row(null, "A", "Trùng 2", "0002.0001", "M5D-" + stamp, null),
                row(null, "A", "Đã dùng", "0002.0001", "m5-admin-0002", null),
                row(null, "A", "Sai dạng", "0002.0001", "có dấu", null),
                row(null, "A", "Super", "0002.0001", "m5s-" + stamp, "Super Admin"),
                row(existingCode, "Trần Văn", "Có sẵn", "0002.0001", "khac-" + stamp, null),
                row(myCode, "Tự", "Hạ quyền", "0002", null, "Người dùng"),
                row(null, "A", "Quyền lạ", "0002.0001", null, "Trưởng ban")))));
        int before = count("SELECT COUNT(*) FROM app_user");
        Res rejected = upload("/api/v1/users/import?dryRun=true", admin0002, bad);
        assertThat(errorCells(rejected.body())).as(String.valueOf(rejected.body())).containsExactlyInAnyOrder(
                "2|Tài khoản", "4|Tài khoản", "5|Tài khoản", "6|Tài khoản", "7|Quyền", "8|Tài khoản", "9|Quyền", "10|Quyền");
        assertThat(rejected.body().toString()).contains("Super Admin không cấp bằng Excel").contains("đã có tài khoản \\\"m5e-");
        assertThat(upload("/api/v1/users/import?dryRun=false", admin0002, bad).body().get("written").asBoolean()).isFalse();
        assertThat(count("SELECT COUNT(*) FROM app_user")).isEqualTo(before);
    }

    // ------------------------------------------------------------------ M5.3 lớp

    @Test
    void nhapNhieuLopKemNguoiHoc() throws Exception {
        long period = period(sa);
        String c1 = jdbc.queryForObject("SELECT personal_code FROM app_user WHERE id = ?", String.class, person(sa, "0002.0001", "Một"));
        String c2 = jdbc.queryForObject("SELECT personal_code FROM app_user WHERE id = ?", String.class, person(sa, "0003", "Hai"));
        String a = uniq("Lớp A");
        String b = uniq("Lớp B");
        String url = "/api/v1/classes/import?periodId=" + period + "&dryRun=";

        Map<String, List<List<Object>>> bad = new LinkedHashMap<>();
        bad.put("NoiDung", List.of(header("Tên nội dung *|Ngày kiểm tra|Địa điểm|Ghi chú"),
                row(a, "20/11/2026", "Thao trường"),
                row(b, "31/02/2026"),
                row(a, "20/11/2026")));
        bad.put("NguoiHoc", List.of(header("Tên nội dung *|Mã định danh *"),
                row(a, c1), row(a, c2), row(a, c1), row(a, "MA-KHONG-CO"), row("Nội dung lạ", c2)));
        int classesBefore = count("SELECT COUNT(*) FROM exam_class");
        Res rejected = upload(url + "false", sa, xlsx(bad));
        assertThat(rejected.body().get("written").asBoolean()).isFalse();
        List<String> errors = new ArrayList<>();
        rejected.body().get("errors").forEach(e -> errors.add(e.get("sheet").asText() + "|" + e.get("row").asInt()));
        assertThat(errors).containsExactlyInAnyOrder("NoiDung|3", "NoiDung|4", "NguoiHoc|4", "NguoiHoc|5", "NguoiHoc|6");
        assertThat(rejected.body().toString()).contains("dd/mm/yyyy", "MA-KHONG-CO", "đã có trong nội dung kiểm tra");
        assertThat(count("SELECT COUNT(*) FROM exam_class")).isEqualTo(classesBefore);

        Map<String, List<List<Object>>> good = new LinkedHashMap<>();
        good.put("NoiDung", List.of(header("Tên nội dung *|Ngày kiểm tra|Địa điểm|Ghi chú"),
                row(a, "20/11/2026", "Thao trường"), row(b)));
        good.put("NguoiHoc", List.of(header("Tên nội dung *|Mã định danh *"), row(a, c1), row(a, c2), row(b, c1)));
        Res dry = upload(url + "true", sa, xlsx(good));
        assertThat(dry.body().get("counts").get("CREATE").asInt()).as(String.valueOf(dry.body())).isEqualTo(2);
        Res write = upload(url + "false", sa, xlsx(good));
        assertThat(write.body().get("written").asBoolean()).isTrue();
        long classA = jdbc.queryForObject("SELECT id FROM exam_class WHERE name = ?", Long.class, a);
        assertThat(jdbc.queryForObject("SELECT exam_date FROM exam_class WHERE id = ?", String.class, classA)).isEqualTo("2026-11-20");
        assertThat(count("SELECT COUNT(*) FROM class_participant WHERE class_id = ?", classA)).isEqualTo(2);
        assertThat(count("""
                SELECT COUNT(*) FROM class_participant cp JOIN unit un ON un.id = cp.unit_id_snapshot
                WHERE cp.class_id = ? AND un.code = '0003'""", classA)).as("đơn vị lúc vào lớp").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action = ?", AuditAction.CLASSES_IMPORTED)).isGreaterThanOrEqualTo(1);

        assertThat(upload(url + "true", admin0002, xlsx(good)).status()).as("chỉ Super Admin").isEqualTo(403);
        download("/api/v1/classes/import-template", admin0002, 403);
        download("/api/v1/classes/import-template", sa, 200);
        Map<String, List<List<Object>>> unknownPeriod = new LinkedHashMap<>();
        unknownPeriod.put("NoiDung", List.of(header("Tên nội dung *|Đợt kiểm tra *"), row(uniq("Nội dung C"), "Đợt không có")));
        Res noPeriod = upload("/api/v1/classes/import?dryRun=true", sa, xlsx(unknownPeriod));
        assertThat(errorCells(noPeriod.body())).containsExactly("2|Đợt kiểm tra");
        ok(patch("/api/v1/periods/" + period + "/status"), sa, Map.of("status", "CLOSED"), 200);
        assertThat(upload(url + "true", sa, xlsx(good)).status()).as("đợt đã đóng").isEqualTo(400);
    }

    // ------------------------------------------------------------------ M5.4 điểm

    @Test
    void nhapDiemTheoLopKhongGhiDeVaKhongTuCham() throws Exception {
        long classId = newClass(sa);
        setTeachers(sa, classId, teacherId);
        long p1 = person(sa, "0002.0001", "Một");
        long p2 = person(sa, "0002.0001", "Hai");
        long p3 = person(sa, "0003", "Ba");
        long pid1 = addParticipant(sa, classId, p1);
        addParticipant(sa, classId, p2);
        long pid3 = addParticipant(sa, classId, p3);
        long selfPid = addParticipant(sa, classId, teacherId);
        long unitPid = ok(post("/api/v1/classes/" + classId + "/participants"), sa, Map.of("unitId", unitId("0004")), 201).id();
        ok(post("/api/v1/classes/" + classId + "/participants/" + pid3 + "/result"), sa, Map.of("score", 7), 201);
        long reasonId = jdbc.queryForObject("SELECT id FROM absence_reason WHERE active = 1 ORDER BY id LIMIT 1", Long.class);
        ok(put("/api/v1/classes/" + classId + "/participants/" + pid1 + "/absence"), teacher, Map.of("reasonId", reasonId), 200);
        Map<Long, String> code = new HashMap<>();
        for (long id : new long[] {p1, p2, p3, teacherId}) {
            code.put(id, jdbc.queryForObject("SELECT personal_code FROM app_user WHERE id = ?", String.class, id));
        }

        // File mẫu: chỉ người chưa có điểm, dòng cả đơn vị dùng mã đơn vị.
        String base = "/api/v1/classes/" + classId + "/results/";
        byte[] tpl = download(base + "import-template", teacher, 200);
        List<String> keys = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(tpl))) {
            Sheet s = wb.getSheet("Diem");
            int head = headerRow(s, "STT");
            Map<String, Row> byCode = new HashMap<>();
            for (int r = head + 1; r <= s.getLastRowNum(); r++) {
                String v = s.getRow(r) == null || s.getRow(r).getCell(1) == null ? "" : s.getRow(r).getCell(1).getStringCellValue();
                if (!v.isEmpty()) {
                    keys.add(v);
                    byCode.put(v, s.getRow(r));
                }
            }
            assertThat(((XSSFSheet) s).getProtect()).as("khóa cột điền sẵn").isTrue();
            Row first = byCode.get(code.get(p1));
            assertThat(first.getCell(1).getCellStyle().getLocked()).isTrue();
            assertThat(first.getCell(6).getCellStyle().getLocked()).as("mở ô Điểm của người chưa có điểm").isFalse();
            assertThat(first.getCell(5).getStringCellValue()).as("tình trạng").startsWith("Không kiểm tra");
            Row gradedRow = byCode.get(code.get(p3));
            assertThat(gradedRow.getCell(6).getNumericCellValue()).as("điểm đã có điền sẵn").isEqualTo(7.0);
            assertThat(gradedRow.getCell(6).getCellStyle().getLocked()).as("khóa ô điểm đã có").isTrue();
            assertThat(byCode.get(code.get(teacherId)).getCell(6).getCellStyle().getLocked()).as("khóa ô tự chấm").isTrue();
            assertThat(s.getRow(head + keys.size() + 1)).as("không có dòng thừa").isNull();
        }
        assertThat(keys).as("danh sách đầy đủ của lớp").containsExactlyInAnyOrder(code.get(p1), code.get(p2), code.get(p3),
                code.get(teacherId), "0004");

        String h = "Mã định danh *|Họ tên|Đơn vị|Điểm";
        byte[] bad = xlsx(Map.of("Diem", List.of(header(h),
                row(code.get(p1), "Một", null, 6.5),
                row(code.get(p3), "Ba", null, 9),
                row(code.get(p2), "Hai", null, "6.55"),
                row("0004", null, null, 11),
                row(code.get(teacherId), null, null, 8),
                row("KHONG-CO", null, null, 5),
                row(code.get(p1), null, null, 7))));
        Res rejected = upload(base + "import?dryRun=false", teacher, bad);
        assertThat(rejected.body().get("written").asBoolean()).isFalse();
        assertThat(errorCells(rejected.body())).containsExactlyInAnyOrder(
                "3|Điểm", "4|Điểm", "5|Điểm", "6|Mã định danh", "7|Mã định danh", "8|Mã định danh");
        assertThat(rejected.body().toString()).contains("đã có điểm 7", "Không tự chấm");
        assertThat(count("SELECT COUNT(*) FROM result r JOIN class_participant cp ON cp.id = r.participant_id WHERE cp.class_id = ?",
                classId)).as("không ghi nửa chừng").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT score_x10 FROM result WHERE participant_id = ?", Integer.class, pid3))
                .as("không ghi đè").isEqualTo(70);

        Res text = upload(base + "import?dryRun=true", teacher,
                xlsx(Map.of("Diem", List.of(header(h), row(code.get(p2), null, null, "abc")))));
        assertThat(errorCells(text.body())).containsExactly("2|Điểm");

        byte[] good = xlsx(Map.of("Diem", List.of(header(h),
                row(code.get(p1), null, null, "6,5"),
                row(code.get(p2), null, null, null),
                row(code.get(p3), null, null, 7),
                row("0004", null, null, 8))));
        Res dry = upload(base + "import?dryRun=true", teacher, good);
        assertThat(dry.body().get("counts").get("GRADE").asInt()).as(String.valueOf(dry.body())).isEqualTo(2);
        assertThat(dry.body().get("counts").get("SKIP").asInt()).as("ô trống và điểm đã có giữ nguyên").isEqualTo(2);
        Res write = upload(base + "import?dryRun=false", teacher, good);
        assertThat(write.body().get("written").asBoolean()).isTrue();
        assertThat(jdbc.queryForMap("SELECT score_x10, grade, graded_by FROM result WHERE participant_id = ?", pid1))
                .containsEntry("score_x10", 65).containsEntry("grade", "PASS").containsEntry("graded_by", (int) teacherId);
        assertThat(jdbc.queryForObject("SELECT score_x10 FROM result WHERE participant_id = ?", Integer.class, unitPid)).isEqualTo(80);
        assertThat(count("SELECT COUNT(*) FROM class_participant WHERE id = ? AND absence_reason_id IS NULL", pid1))
                .as("nhập điểm xóa lý do vắng").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM result WHERE participant_id = ?", selfPid)).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_id = ?", AuditAction.RESULTS_IMPORTED, classId)).isEqualTo(1);

        // Người không quản lý lớp: 404 cả file mẫu lẫn nhập.
        download(base + "import-template", admin0002, 404);
        assertThat(upload(base + "import?dryRun=true", admin0002, good).status()).isEqualTo(404);
        assertThat(upload(base + "import?dryRun=true", plainUser, good).status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ xuất điểm (2026-11-21)

    /** Các dòng dữ liệu của sheet xuất, theo mã định danh (cột 1). */
    static Map<String, Row> exportRows(Sheet s) {
        int head = headerRow(s, "STT");
        Map<String, Row> out = new LinkedHashMap<>();
        for (int r = head + 1; r <= s.getLastRowNum(); r++) {
            Row row = s.getRow(r);
            if (row != null && row.getCell(1) != null && !row.getCell(1).getStringCellValue().isEmpty()) {
                out.put(row.getCell(1).getStringCellValue(), row);
            }
        }
        return out;
    }

    static String text(Sheet s) {
        StringBuilder b = new StringBuilder();
        s.forEach(r -> r.forEach(c -> b.append(new DataFormatter().formatCellValue(c)).append('|')));
        return b.toString();
    }

    @Test
    void xuatDiemMotNoiDungTheoPhamViNguoiXem() throws Exception {
        long classId = newClass(sa);
        setTeachers(sa, classId, teacherId);
        long p1 = person(sa, "0002.0001", "Vắng");
        long p2 = person(sa, "0002.0001", "Giỏi");
        long p3 = person(sa, "0003", "Khá");
        long pid1 = addParticipant(sa, classId, p1);
        long pid2 = addParticipant(sa, classId, p2);
        long pid3 = addParticipant(sa, classId, p3);
        long unitPid = ok(post("/api/v1/classes/" + classId + "/participants"), sa, Map.of("unitId", unitId("0004")), 201).id();
        ok(post("/api/v1/classes/" + classId + "/participants/" + pid2 + "/result"), teacher, Map.of("score", 8.5), 201);
        ok(post("/api/v1/classes/" + classId + "/participants/" + pid3 + "/result"), teacher, Map.of("score", 7), 201);
        ok(post("/api/v1/classes/" + classId + "/participants/" + unitPid + "/result"), teacher, Map.of("score", 5), 201);
        long reasonId = jdbc.queryForObject("SELECT id FROM absence_reason WHERE active = 1 ORDER BY id LIMIT 1", Long.class);
        String reason = jdbc.queryForObject("SELECT name FROM absence_reason WHERE id = ?", String.class, reasonId);
        ok(put("/api/v1/classes/" + classId + "/participants/" + pid1 + "/absence"), teacher, Map.of("reasonId", reasonId), 200);
        Map<Long, String> code = new HashMap<>();
        for (long id : new long[] {p1, p2, p3}) {
            code.put(id, jdbc.queryForObject("SELECT personal_code FROM app_user WHERE id = ?", String.class, id));
        }
        String url = "/api/v1/classes/" + classId + "/results/export";

        for (MockHttpSession who : List.of(sa, teacher)) {
            try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(download(url, who, 200)))) {
                assertThat(wb.getNumberOfSheets()).as("file xuất chỉ có sheet kết quả").isEqualTo(1);
                Sheet s = wb.getSheet("KetQua");
                int head = headerRow(s, "STT");
                assertThat(head).as("khối tiêu đề, thông tin như file mẫu").isGreaterThan(4);
                List<String> headers = new ArrayList<>();
                s.getRow(head).forEach(c -> headers.add(c.getStringCellValue()));
                assertThat(headers).containsExactly("STT", "Mã định danh", "Họ tên", "Cấp bậc", "Đối tượng", "Đơn vị", "Điểm",
                        "Xếp loại", "Tình trạng", "Ghi chú", "Người chấm", "Thời gian chấm");
                assertThat(((XSSFSheet) s).getProtect()).as("file xuất không khóa").isFalse();
                assertThat(s.getPaneInformation().isFreezePane()).isTrue();
                Map<String, Row> rows = exportRows(s);
                assertThat(rows.keySet()).as("cả nội dung, dòng cả đơn vị dùng mã đơn vị")
                        .containsExactlyInAnyOrder(code.get(p1), code.get(p2), code.get(p3), "0004");
                Row good = rows.get(code.get(p2));
                assertThat(good.getCell(6).getNumericCellValue()).isEqualTo(8.5);
                assertThat(good.getCell(6).getCellStyle().getDataFormatString()).isEqualTo("0.0");
                assertThat(good.getCell(7).getStringCellValue()).isEqualTo("Giỏi");
                assertThat(good.getCell(4).getStringCellValue()).as("đối tượng").isEqualTo("SQ");
                assertThat(good.getCell(8).getStringCellValue()).isEqualTo("Đã kiểm tra");
                assertThat(good.getCell(11).getStringCellValue()).matches("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}");
                assertThat(rows.get(code.get(p3)).getCell(7).getStringCellValue()).isEqualTo("Khá");
                Row absent = rows.get(code.get(p1));
                assertThat(absent.getCell(8).getStringCellValue()).isEqualTo("Không kiểm tra");
                assertThat(absent.getCell(9).getStringCellValue()).isEqualTo(reason);
                assertThat(rows.get("0004").getCell(3).getStringCellValue()).isEqualTo("Cả đơn vị");
                assertThat(text(s)).as("tổng hợp không trộn điểm cá nhân với điểm đơn vị")
                        .contains("Sĩ số: 4", "Đã kiểm tra: 3", "Không kiểm tra: 1", "Chưa kiểm tra: 0",
                                "Xếp loại cá nhân: Giỏi 1   ·   Khá 1   ·   Đạt 0   ·   Không đạt 0",
                                "Xếp loại đơn vị: Giỏi 0   ·   Khá 0   ·   Đạt 1   ·   Không đạt 0",
                                "toàn bộ người học");
            }
        }

        // Admin 0002 chỉ có người trong nhánh mình (như danh sách trên trang); người không liên quan nhận 404.
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(download(url, admin0002, 200)))) {
            Sheet s = wb.getSheet("KetQua");
            assertThat(exportRows(s).keySet()).containsExactlyInAnyOrder(code.get(p1), code.get(p2));
            assertThat(text(s)).contains("Sĩ số: 2", "đơn vị bạn quản lý").doesNotContain("Xếp loại đơn vị");
        }
        download(url, plainUser, 404);
        download("/api/v1/classes/999999/results/export", sa, 404);
    }
}
