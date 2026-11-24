package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.trainingstats.audit.AuditAction;

/**
 * M2.4: phân quyền quản lý người dùng theo nhánh, qua API thật.
 * Cây dùng trong test: 0002 (Phòng Tham mưu, cấp 1) có con 0002.0001..0002.0005; 0003 là nhánh khác.
 */
class UserApiTest extends WebTestBase {

    private static final String PW = "MatKhau@1";

    private MockHttpSession sa;
    private MockHttpSession admin0002;      // Admin cấp 1, Phòng Tham mưu
    private MockHttpSession admin00020001;  // Admin cấp 2, Ban Quân huấn

    @BeforeEach
    void setUp() throws Exception {
        ensureUser("m2-admin-0002", "ADMIN", "0002", PW, "ACTIVE");
        ensureUser("m2-admin-00020001", "ADMIN", "0002.0001", PW, "ACTIVE");
        sa = login(SA_USERNAME, SA_PASSWORD);
        admin0002 = login("m2-admin-0002", PW);
        admin00020001 = login("m2-admin-00020001", PW);
    }

    // ------------------------------------------------------------------ tiện ích

    private record Res(int status, JsonNode body) {
    }

    private Res call(MockHttpServletRequestBuilder b, MockHttpSession s, Object body) throws Exception {
        b = b.session(s);
        if (body != null) {
            b = b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MvcResult r = mvc.perform(b.with(csrf())).andReturn();
        String text = r.getResponse().getContentAsString();
        return new Res(r.getResponse().getStatus(), text.isEmpty() ? null : json.readTree(text));
    }

    private static int seq = 0;

    /** Tạo người dùng qua API bằng phiên {@code s}, trả id. */
    private long createUser(MockHttpSession s, String unitCode, String role, String username) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("category", "SQ");
        body.put("lastName", "Nguyễn Văn");
        body.put("firstName", "Thử" + (++seq));
        body.put("personalCode", "M2-" + System.nanoTime());
        body.put("unitId", unitId(unitCode));
        body.put("role", role);
        if (username != null) {
            body.put("username", username + "-" + System.nanoTime());
            body.put("password", PW);
        }
        Res r = call(post("/api/v1/users"), s, body);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    private int version(long id) {
        return jdbc.queryForObject("SELECT version FROM app_user WHERE id = ?", Integer.class, id);
    }

    // ------------------------------------------------------------------ mã định danh tự cấp

    private Res createWithCode(String code) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("category", "SQ");
        body.put("lastName", "Lê Văn");
        body.put("firstName", "Mã" + (++seq));
        body.put("unitId", unitId("0002.0001"));
        if (code != null) {
            body.put("personalCode", code);
        }
        return call(post("/api/v1/users"), admin00020001, body);
    }

    private static long number(String code) {
        assertThat(code).matches("ND\\d{6,}");
        return Long.parseLong(code.substring(2));
    }

    @Test
    void maDinhDanhDeTrongThiTuCapTheoThuTuVaKhongTrung() throws Exception {
        Res a = createWithCode(null);
        assertThat(a.status()).as(String.valueOf(a.body())).isEqualTo(201);
        long n = number(a.body().get("personalCode").asText());
        Res b = createWithCode("  ");
        assertThat(b.status()).isEqualTo(201);
        assertThat(number(b.body().get("personalCode").asText())).isEqualTo(n + 1);

        // Mã tự gõ đúng dạng ND... lớn hơn thì lần cấp sau nhảy qua nó, không bao giờ trùng.
        String manual = String.format("ND%06d", n + 50);
        assertThat(createWithCode(manual).status()).isEqualTo(201);
        assertThat(number(createWithCode(null).body().get("personalCode").asText())).isEqualTo(n + 51);
        // Mã chính thức khác dạng vẫn dùng được và không ảnh hưởng dãy số.
        String official = "SH-" + System.nanoTime();
        assertThat(createWithCode(official).body().get("personalCode").asText()).isEqualTo(official);
        assertThat(number(createWithCode(null).body().get("personalCode").asText())).isEqualTo(n + 52);
        // Trùng mã tự gõ: 409, chỉ rõ trường.
        Res dup = createWithCode(manual);
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.body().toString()).contains("personalCode");

        // Khi sửa thì mã bắt buộc (không xóa được), nhưng đổi được.
        long id = a.body().get("id").asLong();
        Map<String, Object> upd = new HashMap<>();
        upd.put("category", "QNCN");
        upd.put("lastName", "Lê Văn");
        upd.put("firstName", "Sửa");
        upd.put("personalCode", "");
        upd.put("version", version(id));
        Res blank = call(put("/api/v1/users/" + id), admin00020001, upd);
        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.body().toString()).contains("personalCode");
        upd.put("personalCode", "SH-SUA-" + System.nanoTime());
        assertThat(call(put("/api/v1/users/" + id), admin00020001, upd).status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ phạm vi nhánh

    @Test
    void adminNhanhANhanh404VoiNguoiNhanhKhacVaCapTren() throws Exception {
        long other = createUser(sa, "0003", "USER", null);          // nhánh khác
        long upper = createUser(sa, "0002", "USER", null);          // cấp trên của admin 0002.0001

        for (long id : new long[] {other, upper}) {
            assertThat(call(get("/api/v1/users/" + id), admin00020001, null).status()).isEqualTo(404);
            assertThat(call(put("/api/v1/users/" + id), admin00020001,
                    Map.of("category", "SQ", "lastName", "X", "firstName", "Y", "personalCode", "Z", "version", 0)).status()).isEqualTo(404);
            assertThat(call(patch("/api/v1/users/" + id + "/status"), admin00020001, Map.of("status", "INACTIVE")).status())
                    .isEqualTo(404);
            assertThat(call(post("/api/v1/users/" + id + "/move"), admin00020001, Map.of("unitId", unitId("0002.0001")))
                    .status()).isEqualTo(404);
        }
        // Danh sách không chứa họ.
        Res list = call(get("/api/v1/users").param("size", "100"), admin00020001, null);
        List<Long> ids = list.body().get("items").findValues("id").stream().map(JsonNode::asLong).toList();
        assertThat(ids).doesNotContain(other, upper);
        // Không tạo được người vào đơn vị ngoài phạm vi.
        Res outside = call(post("/api/v1/users"), admin00020001, Map.of("category", "SQ", "lastName", "A", "firstName", "B",
                "personalCode", "OUT-" + System.nanoTime(), "unitId", unitId("0003")));
        assertThat(outside.status()).isEqualTo(400);
        assertThat(outside.body().toString()).contains("unitId");
        // Lọc theo đơn vị ngoài phạm vi trả 404.
        assertThat(call(get("/api/v1/users").param("unitId", String.valueOf(unitId("0003"))), admin00020001, null).status())
                .isEqualTo(404);
    }

    @Test
    void adminSuaDuocNguoiTrongNhanhVaNhanhCon() throws Exception {
        long child = createUser(admin0002, "0002.0003", "USER", null);
        Res r = call(put("/api/v1/users/" + child), admin0002, Map.of("category", "SQ", "lastName", "Trần Thị", "firstName", "Bình",
                "personalCode", "SUA-" + System.nanoTime(), "version", version(child)));
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
        assertThat(r.body().get("fullName").asText()).isEqualTo("Trần Thị Bình");
        // Tìm kiếm không dấu ra người có dấu.
        Res found = call(get("/api/v1/users").param("q", "tran thi binh"), admin0002, null);
        assertThat(found.body().get("items").findValues("id").stream().map(JsonNode::asLong).toList()).contains(child);
        assertThat(countAuditFor(AuditAction.USER_UPDATED, child)).isEqualTo(1);
    }

    @Test
    void chuyenSangNhanhKhacThiAdminCuMatQuyenNgay() throws Exception {
        long u = createUser(admin0002, "0002.0002", "USER", null);
        Res moved = call(post("/api/v1/users/" + u + "/move"), admin0002, Map.of("unitId", unitId("0004")));
        assertThat(moved.status()).isEqualTo(200);
        assertThat(moved.body().get("stillInScope").asBoolean()).isFalse();
        assertThat(moved.body().get("user").isNull()).isTrue();
        assertThat(call(get("/api/v1/users/" + u), admin0002, null).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT un.code FROM app_user u JOIN unit un ON un.id = u.unit_id WHERE u.id = ?",
                String.class, u)).isEqualTo("0004");
    }

    @Test
    void r1AdminCap2ChuyenMotAdminSangDonViCap1ThiNguoiDoBiHaVeUser() throws Exception {
        long target = createUser(admin00020001, "0002.0001", "ADMIN", "m2-r1");
        Res moved = call(post("/api/v1/users/" + target + "/move"), admin00020001, Map.of("unitId", unitId("0003")));
        assertThat(moved.status()).isEqualTo(200);
        assertThat(moved.body().get("downgraded").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE id = ?", String.class, target)).isEqualTo("USER");
        assertThat(jdbc.queryForObject("SELECT after_json FROM audit_log WHERE action = ? AND entity_id = ?", String.class,
                AuditAction.USER_MOVED, target)).contains("\"downgraded\":true");

        // Chuyển trong phạm vi thì giữ quyền ADMIN.
        long inside = createUser(admin0002, "0002.0001", "ADMIN", "m2-r1b");
        Res ok = call(post("/api/v1/users/" + inside + "/move"), admin0002, Map.of("unitId", unitId("0002.0004")));
        assertThat(ok.body().get("downgraded").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE id = ?", String.class, inside)).isEqualTo("ADMIN");

        // Super Admin chuyển thì không hạ quyền.
        long bySa = createUser(sa, "0002.0001", "ADMIN", "m2-r1c");
        assertThat(call(post("/api/v1/users/" + bySa + "/move"), sa, Map.of("unitId", unitId("0003"))).body()
                .get("downgraded").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ tự thao tác, Super Admin

    @Test
    void khongTuKhoaTuXoaTuHaQuyenTuChuyen() throws Exception {
        long me = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'm2-admin-0002'", Long.class);
        for (String st : new String[] {"INACTIVE", "DELETED"}) {
            Res r = call(patch("/api/v1/users/" + me + "/status"), admin0002, Map.of("status", st));
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.body().get("code").asText()).isEqualTo("SELF_ACTION");
        }
        assertThat(call(put("/api/v1/users/" + me + "/role"), admin0002, Map.of("role", "USER")).body().get("code").asText())
                .isEqualTo("SELF_ACTION");
        assertThat(call(post("/api/v1/users/" + me + "/move"), admin0002, Map.of("unitId", unitId("0002.0001"))).status())
                .isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT status || role FROM app_user WHERE id = ?", String.class, me))
                .isEqualTo("ACTIVEADMIN");
    }

    @Test
    void luonConMotSuperAdminVaSuperAdminKhongTuKhoaDuoc() throws Exception {
        long saId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, SA_USERNAME);
        Res self = call(patch("/api/v1/users/" + saId + "/status"), sa, Map.of("status", "INACTIVE"));
        assertThat(self.status()).isEqualTo(403);

        // Super Admin thứ hai khóa Super Admin thứ nhất: được, vì vẫn còn một người.
        long sa2 = ensureUser("m2-sa2", "SUPER_ADMIN", null, PW, "ACTIVE");
        MockHttpSession sa2s = login("m2-sa2", PW);
        long sa3 = ensureUser("m2-sa3", "SUPER_ADMIN", null, PW, "ACTIVE");
        assertThat(call(patch("/api/v1/users/" + sa3 + "/status"), sa2s, Map.of("status", "INACTIVE")).status()).isEqualTo(200);
        // Đưa về như cũ để không ảnh hưởng test khác.
        assertThat(call(patch("/api/v1/users/" + sa3 + "/status"), sa2s, Map.of("status", "ACTIVE")).status()).isEqualTo(200);
        assertThat(sa2).isPositive();
    }

    @Test
    void adminKhongCapDuocSuperAdminKhongResetDuocSuperAdmin() throws Exception {
        long u = createUser(admin0002, "0002.0005", "USER", "m2-cap");
        Res r = call(put("/api/v1/users/" + u + "/role"), admin0002, Map.of("role", "SUPER_ADMIN"));
        assertThat(r.status()).isEqualTo(403);
        Res c = call(post("/api/v1/users"), admin0002, Map.of("category", "SQ", "lastName", "A", "firstName", "B",
                "personalCode", "SA-TRY-" + System.nanoTime(), "role", "SUPER_ADMIN", "username", "satry" + System.nanoTime(),
                "password", PW));
        assertThat(c.status()).isEqualTo(403);

        long saId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, SA_USERNAME);
        assertThat(call(post("/api/v1/users/" + saId + "/reset-password"), admin0002, Map.of("newPassword", "Moi@12345"))
                .status()).isEqualTo(404);
        // Admin cấp quyền ADMIN trong nhánh thì được (Q2).
        assertThat(call(put("/api/v1/users/" + u + "/role"), admin0002, Map.of("role", "ADMIN")).body().get("role").asText())
                .isEqualTo("ADMIN");
    }

    @Test
    void datLaiMatKhauChoNguoiTrongNhanh() throws Exception {
        long u = createUser(admin0002, "0002.0005", "USER", "m2-reset");
        String username = jdbc.queryForObject("SELECT username FROM app_user WHERE id = ?", String.class, u);
        assertThat(call(post("/api/v1/users/" + u + "/reset-password"), admin0002, Map.of("newPassword", "ngan")).status())
                .isEqualTo(400);
        assertThat(call(post("/api/v1/users/" + u + "/reset-password"), admin0002, Map.of("newPassword", "DatLai@2026"))
                .status()).isEqualTo(204);
        assertThat(loginResult(username, "DatLai@2026").getResponse().getStatus()).isEqualTo(200);
        assertThat(countAuditFor(AuditAction.PASSWORD_RESET, u)).isEqualTo(1);
        // Không đặt lại cho chính mình (dùng trang đổi mật khẩu).
        long me = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'm2-admin-0002'", Long.class);
        assertThat(call(post("/api/v1/users/" + me + "/reset-password"), admin0002, Map.of("newPassword", "DatLai@2026"))
                .status()).isEqualTo(400);
    }

    @Test
    void capDoiThuHoiTaiKhoanOTrangChiTiet() throws Exception {
        long u = createUser(admin0002, "0002.0003", "USER", null);
        String name = "m2-cap-" + System.nanoTime();
        Res bad = call(put("/api/v1/users/" + u + "/account"), admin0002, Map.of("username", "có dấu"));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().toString()).contains("username");

        // Cấp: nhận mật khẩu mặc định, đăng nhập được ngay, ghi nhật ký không chứa mật khẩu.
        Res granted = call(put("/api/v1/users/" + u + "/account"), admin0002, Map.of("username", name));
        assertThat(granted.status()).as(String.valueOf(granted.body())).isEqualTo(200);
        assertThat(granted.body().get("hasPassword").asBoolean()).isTrue();
        assertThat(loginResult(name, "123456789").getResponse().getStatus()).isEqualTo(200);
        String after = jdbc.queryForObject("SELECT after_json FROM audit_log WHERE action = ? AND entity_id = ? ORDER BY id DESC LIMIT 1",
                String.class, AuditAction.USER_UPDATED, u);
        assertThat(after).contains("CREATED").doesNotContain("123456789").doesNotContain("$2");
        assertThat(call(get("/api/v1/users").param("q", name), admin0002, null).body().get("total").asInt())
                .as("tìm được theo tài khoản").isEqualTo(1);

        // Đổi tên giữ mật khẩu đang có.
        assertThat(call(post("/api/v1/users/" + u + "/reset-password"), admin0002, Map.of("newPassword", "DoiTen@2026"))
                .status()).isEqualTo(204);
        Res renamed = call(put("/api/v1/users/" + u + "/account"), admin0002, Map.of("username", name + "-moi"));
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(loginResult(name + "-moi", "DoiTen@2026").getResponse().getStatus()).isEqualTo(200);
        // Trùng tài khoản của người khác: 409, chỉ rõ trường.
        Res dup = call(put("/api/v1/users/" + u + "/account"), admin0002, Map.of("username", "m2-admin-00020001"));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.body().toString()).contains("username");

        // Sửa hồ sơ không còn đụng tới tài khoản.
        Res edit = call(put("/api/v1/users/" + u), admin0002, Map.of("category", "SQ", "lastName", "Lê", "firstName", "Sửa",
                "personalCode", "ACC-" + System.nanoTime(), "username", "", "version", version(u)));
        assertThat(edit.status()).isEqualTo(200);
        assertThat(edit.body().get("username").asText()).isEqualTo(name + "-moi");

        // Admin không thu hồi được tài khoản; Người dùng thì được, mất luôn mật khẩu.
        assertThat(call(put("/api/v1/users/" + u + "/role"), admin0002, Map.of("role", "ADMIN")).status()).isEqualTo(200);
        Map<String, Object> blank = new HashMap<>();
        blank.put("username", null);
        assertThat(call(put("/api/v1/users/" + u + "/account"), admin0002, blank).status()).isEqualTo(400);
        assertThat(call(put("/api/v1/users/" + u + "/role"), admin0002, Map.of("role", "USER")).status()).isEqualTo(200);
        Res removed = call(put("/api/v1/users/" + u + "/account"), admin0002, blank);
        assertThat(removed.status()).isEqualTo(200);
        assertThat(removed.body().get("username").isNull()).isTrue();
        assertThat(removed.body().get("hasPassword").asBoolean()).isFalse();

        // Ngoài phạm vi: 404; user thường: 403.
        long other = createUser(sa, "0003", "USER", null);
        assertThat(call(put("/api/v1/users/" + other + "/account"), admin0002, Map.of("username", name + "-x")).status())
                .isEqualTo(404);
        ensureUser("m2-user-acc", "USER", "0002.0003", PW, "ACTIVE");
        assertThat(call(put("/api/v1/users/" + u + "/account"), login("m2-user-acc", PW), Map.of("username", name + "-y"))
                .status()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ xóa mềm, kiểm tra dữ liệu

    @Test
    void xoaMemKhongMatBanGhiVaKhongHienTrongDanhSachMacDinh() throws Exception {
        long u = createUser(admin0002, "0002.0004", "USER", null);
        assertThat(call(patch("/api/v1/users/" + u + "/status"), admin0002, Map.of("status", "DELETED")).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, u)).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM app_user WHERE id = ?", Boolean.class, u)).isTrue();
        Res list = call(get("/api/v1/users").param("size", "100"), admin0002, null);
        assertThat(list.body().get("items").findValues("id").stream().map(JsonNode::asLong).toList()).doesNotContain(u);
        Res deleted = call(get("/api/v1/users").param("status", "DELETED").param("size", "100"), admin0002, null);
        assertThat(deleted.body().get("items").findValues("id").stream().map(JsonNode::asLong).toList()).contains(u);
        // Khôi phục.
        assertThat(call(patch("/api/v1/users/" + u + "/status"), admin0002, Map.of("status", "ACTIVE")).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM app_user WHERE id = ?", String.class, u)).isNull();
    }

    @Test
    void kiemTraDuLieuVaTrung() throws Exception {
        Map<String, Object> missing = Map.of("unitId", unitId("0002"));
        Res r = call(post("/api/v1/users"), admin0002, missing);
        assertThat(r.status()).isEqualTo(400);
        // Mã định danh không bắt buộc khi tạo: để trống thì hệ thống tự cấp.
        assertThat(r.body().get("fieldErrors").findValues("field").stream().map(JsonNode::asText).toList())
                .contains("lastName", "firstName").doesNotContain("personalCode");

        Res adminNoAccount = call(post("/api/v1/users"), admin0002, Map.of("category", "SQ", "lastName", "A", "firstName", "B",
                "personalCode", "NA-" + System.nanoTime(), "unitId", unitId("0002"), "role", "ADMIN"));
        assertThat(adminNoAccount.body().toString()).contains("username");

        // Có tài khoản mà không nhập mật khẩu thì nhận mật khẩu mặc định.
        String noPassUser = "nopass" + System.nanoTime();
        Res noPassword = call(post("/api/v1/users"), admin0002, Map.of("category", "SQ", "lastName", "A", "firstName", "B",
                "personalCode", "NP-" + System.nanoTime(), "unitId", unitId("0002"), "username", noPassUser));
        assertThat(noPassword.status()).as(String.valueOf(noPassword.body())).isEqualTo(201);
        assertThat(noPassword.body().get("hasPassword").asBoolean()).isTrue();
        assertThat(loginResult(noPassUser, "123456789").getResponse().getStatus()).isEqualTo(200);

        long u = createUser(admin0002, "0002", "USER", null);
        String code = jdbc.queryForObject("SELECT personal_code FROM app_user WHERE id = ?", String.class, u);
        Res dup = call(post("/api/v1/users"), admin0002, Map.of("category", "SQ", "lastName", "A", "firstName", "B",
                "personalCode", code, "unitId", unitId("0002")));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.body().get("fieldErrors").get(0).get("field").asText()).isEqualTo("personalCode");

        // Sai version (người khác đã sửa trước) thì 409.
        Res stale = call(put("/api/v1/users/" + u), admin0002, Map.of("category", "SQ", "lastName", "A", "firstName", "B",
                "personalCode", code, "version", version(u) + 5));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("code").asText()).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    void userThuongChiXemDuocChinhMinh() throws Exception {
        long me = ensureUser("m2-user-thuong", "USER", "0002.0001", PW, "ACTIVE");
        long other = createUser(sa, "0002.0001", "USER", null);
        MockHttpSession s = login("m2-user-thuong", PW);
        assertThat(call(get("/api/v1/users/" + me), s, null).status()).isEqualTo(200);
        assertThat(call(get("/api/v1/users/" + me + "/print"), s, null).status()).isEqualTo(200);
        assertThat(call(get("/api/v1/users/" + other), s, null).status()).isEqualTo(404);
        assertThat(call(get("/api/v1/users"), s, null).status()).isEqualTo(403);
    }

    @Test
    void chiTietCoTenCapBacDayDuVaDuongDanDonVi() throws Exception {
        long u = createUser(admin0002, "0002.0001", "USER", null);
        long rank = jdbc.queryForObject("SELECT id FROM rank WHERE symbol = '3/'", Long.class);
        long spec1 = jdbc.queryForObject("SELECT id FROM specialty WHERE name = 'Hàng hải'", Long.class);
        long spec2 = jdbc.queryForObject("SELECT id FROM specialty WHERE name = 'Radar'", Long.class);
        Map<String, Object> body = new HashMap<>();
        body.put("category", "SQ");
        body.put("lastName", "Lê");
        body.put("firstName", "Cường");
        body.put("personalCode", "IN-" + System.nanoTime());
        body.put("rankId", rank);
        body.put("specialtyIds", List.of(spec1, spec2, spec1));
        body.put("birthDate", "1995-04-30");
        body.put("version", version(u));
        assertThat(call(put("/api/v1/users/" + u), admin0002, body).status()).isEqualTo(200);
        JsonNode v = call(get("/api/v1/users/" + u + "/print"), admin0002, null).body();
        assertThat(v.get("rank").get("name").asText()).isEqualTo("Thượng úy");
        assertThat(v.get("rank").get("symbol").asText()).isEqualTo("3/");
        assertThat(v.get("unit").get("path").asText()).isEqualTo("Phòng Tham mưu / Ban Quân huấn");
        assertThat(v.get("specialties").size()).isEqualTo(2);
        assertThat(v.toString()).doesNotContain("password_hash").doesNotContain("$2");
    }

    @Test
    void locNhieuGiaTriMoiTieuChiVaGiuPhamVi() throws Exception {
        String tag = "Lọcnhiều" + System.nanoTime();
        long rank1 = jdbc.queryForObject("SELECT id FROM rank WHERE symbol = '3/'", Long.class);
        long rank2 = jdbc.queryForObject("SELECT id FROM rank WHERE symbol = '2/'", Long.class);
        long spec1 = jdbc.queryForObject("SELECT id FROM specialty WHERE name = 'Hàng hải'", Long.class);
        long spec2 = jdbc.queryForObject("SELECT id FROM specialty WHERE name = 'Radar'", Long.class);
        long a = tagged(tag, "0002.0001", rank1, spec1);
        long b = tagged(tag, "0002.0002", rank2, spec2);
        long c = tagged(tag, "0002.0003", rank2, spec1);
        long d = tagged(tag, "0003", rank1, spec2);

        // Hai đơn vị cùng lúc (gồm đơn vị con), hai cấp bậc, hai chuyên ngành: "hoặc" trong một tiêu chí, "và" giữa các tiêu chí.
        assertThat(found(sa, tag, "unitId", unitId("0002.0001") + "," + unitId("0002.0002"))).containsExactlyInAnyOrder(a, b);
        assertThat(found(sa, tag, "unitId", unitId("0002") + "," + unitId("0003"))).containsExactlyInAnyOrder(a, b, c, d);
        assertThat(found(sa, tag, "rankId", rank1 + "," + rank2)).containsExactlyInAnyOrder(a, b, c, d);
        assertThat(found(sa, tag, "specialtyId", String.valueOf(spec2))).containsExactlyInAnyOrder(b, d);
        Res both = call(get("/api/v1/users").param("q", tag).param("rankId", String.valueOf(rank2))
                .param("specialtyId", spec1 + "," + spec2).param("unitId", String.valueOf(unitId("0002"))), sa, null);
        assertThat(ids(both)).containsExactlyInAnyOrder(b, c);
        // Lặp tham số cũng được.
        Res repeated = call(get("/api/v1/users").param("q", tag).param("unitId", String.valueOf(unitId("0002.0001")))
                .param("unitId", String.valueOf(unitId("0003"))), sa, null);
        assertThat(ids(repeated)).containsExactlyInAnyOrder(a, d);

        // Trạng thái, quyền chọn nhiều.
        assertThat(call(patch("/api/v1/users/" + c + "/status"), sa, Map.of("status", "INACTIVE")).status()).isEqualTo(200);
        assertThat(call(patch("/api/v1/users/" + d + "/status"), sa, Map.of("status", "DELETED")).status()).isEqualTo(200);
        assertThat(found(sa, tag, "status", "INACTIVE,DELETED")).containsExactlyInAnyOrder(c, d);
        assertThat(found(sa, tag, "status", "ACTIVE")).containsExactlyInAnyOrder(a, b);
        assertThat(found(sa, tag, "role", "USER,ADMIN")).containsExactlyInAnyOrder(a, b, c);
        assertThat(call(get("/api/v1/users").param("status", "ACTIVE,SAI"), sa, null).status()).isEqualTo(400);

        // Admin cấp 2: một đơn vị ngoài phạm vi trong danh sách là 404; trong phạm vi thì chỉ thấy người của nhánh mình.
        assertThat(call(get("/api/v1/users").param("unitId", unitId("0002.0001") + "," + unitId("0002.0002")),
                admin00020001, null).status()).isEqualTo(404);
        assertThat(found(admin00020001, tag, "rankId", rank1 + "," + rank2)).containsExactly(a);
    }

    @Test
    void doiTuongBatBuocKhiTaoSuaVaLocDuoc() throws Exception {
        String tag = "Đốitượng" + System.nanoTime();
        Map<String, Object> body = new HashMap<>();
        body.put("lastName", tag);
        body.put("firstName", "Thiếu");
        body.put("unitId", unitId("0002.0001"));
        Res missing = call(post("/api/v1/users"), admin0002, body);
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.body().toString()).contains("category");
        body.put("category", "ABC");
        assertThat(call(post("/api/v1/users"), admin0002, body).status()).isEqualTo(400);

        body.put("category", "QNCN");
        long q = call(post("/api/v1/users"), admin0002, body).body().get("id").asLong();
        body.put("firstName", "Chiến sĩ");
        body.put("category", "HSQ-CS");
        long c = call(post("/api/v1/users"), admin0002, body).body().get("id").asLong();
        assertThat(call(get("/api/v1/users/" + q), admin0002, null).body().get("category").asText()).isEqualTo("QNCN");

        // Sửa không được bỏ trống đối tượng; đổi được.
        Map<String, Object> upd = new HashMap<>();
        upd.put("lastName", tag);
        upd.put("firstName", "Thiếu");
        upd.put("personalCode", call(get("/api/v1/users/" + q), admin0002, null).body().get("personalCode").asText());
        upd.put("version", version(q));
        assertThat(call(put("/api/v1/users/" + q), admin0002, upd).status()).isEqualTo(400);
        upd.put("category", "SQ");
        assertThat(call(put("/api/v1/users/" + q), admin0002, upd).body().get("category").asText()).isEqualTo("SQ");

        assertThat(found(admin0002, tag, "category", "SQ")).containsExactly(q);
        assertThat(found(admin0002, tag, "category", "SQ,HSQ-CS")).containsExactlyInAnyOrder(q, c);
        assertThat(call(get("/api/v1/users").param("category", "XYZ"), admin0002, null).status()).isEqualTo(400);
        assertThat(call(get("/api/v1/users").param("q", tag), admin0002, null).body().get("items").get(0).has("category"))
                .isTrue();
    }

    private long tagged(String tag, String unitCode, long rankId, long specialtyId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("category", "SQ");
        body.put("lastName", tag);
        body.put("firstName", "Người" + (++seq));
        body.put("personalCode", "LN-" + System.nanoTime());
        body.put("unitId", unitId(unitCode));
        body.put("rankId", rankId);
        body.put("specialtyIds", List.of(specialtyId));
        Res r = call(post("/api/v1/users"), sa, body);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    private List<Long> found(MockHttpSession s, String tag, String param, String value) throws Exception {
        Res r = call(get("/api/v1/users").param("q", tag).param(param, value).param("size", "100"), s, null);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
        return ids(r);
    }

    private static List<Long> ids(Res r) {
        return r.body().get("items").findValues("id").stream().map(JsonNode::asLong).toList();
    }

    private int countAuditFor(String action, long entityId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_id = ?", Integer.class,
                action, entityId);
    }
}
