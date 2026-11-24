package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import vn.trainingstats.audit.AuditAction;

/** M1.3: đăng nhập, đăng xuất, /me, đổi mật khẩu, CSRF, hết phiên, chặn tài khoản không dùng được. */
class AuthApiTest extends WebTestBase {

    @Test
    void layTokenCsrfKhongCanDangNhap() throws Exception {
        mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                // Tên header thật (X-XSRF-TOKEN) kiểm ở HttpHeadersTest: MockMvc bọc kho token bằng bản giả của test.
                .andExpect(jsonPath("$.headerName").isNotEmpty())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void dangNhapDungTraThongTinVaDatThoiGianHetPhien() throws Exception {
        long id = ensureUser("admin-tham-muu", "ADMIN", "0002", "MatKhau@1", "ACTIVE");
        int before = countAudit(AuditAction.LOGIN_SUCCESS, "admin-tham-muu");

        MvcResult r = loginResult("ADMIN-Tham-Muu", "MatKhau@1");   // tài khoản không phân biệt hoa thường
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        MockHttpSession session = (MockHttpSession) r.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getMaxInactiveInterval()).as("30 phút từ app_setting").isEqualTo(30 * 60);

        mvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.adminLevel").value(1))
                .andExpect(jsonPath("$.unit.code").value("0002"))
                .andExpect(jsonPath("$.unit.name").value("Phòng Tham mưu"))
                .andExpect(jsonPath("$.teachingClasses").isArray());

        assertThat(countAudit(AuditAction.LOGIN_SUCCESS, "admin-tham-muu")).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT ip FROM audit_log WHERE action = ? AND actor_id = ? ORDER BY id DESC LIMIT 1",
                String.class, AuditAction.LOGIN_SUCCESS, id)).isEqualTo("127.0.0.1");
        assertThat(jdbc.queryForObject("SELECT last_login_at FROM app_user WHERE id = ?", String.class, id)).isNotNull();
    }

    @Test
    void meTraCacLopDangLamGiaoVienChuaBiXoa() throws Exception {
        long id = ensureUser("gv-me", "USER", "0002.0001", "MatKhau@1", "ACTIVE");
        tx().executeWithoutResult(s -> {
            jdbc.update("INSERT INTO exam_period(name) VALUES ('Dot me test')");
            long period = jdbc.queryForObject("SELECT MAX(id) FROM exam_period", Long.class);
            jdbc.update("INSERT INTO exam_class(period_id, name, exam_date) VALUES (?, 'Lop dang day', '2026-12-01')",
                    period);
            long active = jdbc.queryForObject("SELECT MAX(id) FROM exam_class", Long.class);
            jdbc.update("INSERT INTO exam_class(period_id, name, deleted_at) VALUES (?, 'Lop da xoa', '2026-01-01T00:00:00Z')",
                    period);
            long deleted = jdbc.queryForObject("SELECT MAX(id) FROM exam_class", Long.class);
            jdbc.update("INSERT INTO class_teacher(class_id, user_id) VALUES (?, ?), (?, ?)", active, id, deleted, id);
        });
        MockHttpSession session = login("gv-me", "MatKhau@1");
        mvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teachingClasses.length()").value(1))
                .andExpect(jsonPath("$.teachingClasses[0].name").value("Lop dang day"))
                .andExpect(jsonPath("$.teachingClasses[0].status").value("OPEN"));
    }

    @Test
    void saiMatKhauVaTaiKhoanKhongTonTaiCungMotThongBao() throws Exception {
        ensureUser("user-sai-mk", "USER", "0003", "MatKhau@1", "ACTIVE");
        int before = countAudit(AuditAction.LOGIN_FAILED, "user-sai-mk");

        for (String[] attempt : new String[][] {{"user-sai-mk", "sai-mat-khau"}, {"khong-ton-tai", "MatKhau@1"}}) {
            MvcResult r = loginResult(attempt[0], attempt[1]);
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(r.getResponse().getContentAsString())
                    .contains("INVALID_CREDENTIALS").contains("Tài khoản hoặc mật khẩu không đúng.");
            assertThat(r.getRequest().getSession(false)).as("không tạo phiên khi đăng nhập sai").isNull();
        }
        assertThat(countAudit(AuditAction.LOGIN_FAILED, "user-sai-mk")).isEqualTo(before + 1);
        assertThat(countAudit(AuditAction.LOGIN_FAILED, "khong-ton-tai")).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT after_json FROM audit_log WHERE action = ? AND actor_username = 'user-sai-mk' ORDER BY id DESC LIMIT 1",
                String.class, AuditAction.LOGIN_FAILED)).contains("INVALID_CREDENTIALS");
    }

    @Test
    void taiKhoanBiKhoaXoaHoacKhongCoMatKhauKhongDangNhapDuoc() throws Exception {
        ensureUser("user-bi-khoa", "USER", "0003", "MatKhau@1", "INACTIVE");
        MvcResult locked = loginResult("user-bi-khoa", "MatKhau@1");
        assertThat(locked.getResponse().getStatus()).isEqualTo(401);
        assertThat(locked.getResponse().getContentAsString()).contains("ACCOUNT_LOCKED");
        // Sai mật khẩu thì không tiết lộ là tài khoản bị khóa.
        assertThat(loginResult("user-bi-khoa", "sai").getResponse().getContentAsString()).contains("INVALID_CREDENTIALS");

        ensureUser("user-da-xoa", "USER", "0003", "MatKhau@1", "DELETED");
        assertThat(loginResult("user-da-xoa", "MatKhau@1").getResponse().getContentAsString())
                .contains("INVALID_CREDENTIALS");

        ensureUser("user-khong-mk", "USER", "0003", null, "ACTIVE");
        assertThat(loginResult("user-khong-mk", "").getResponse().getStatus()).isEqualTo(400);   // thiếu mật khẩu
        assertThat(loginResult("user-khong-mk", "bat-ky").getResponse().getContentAsString())
                .contains("INVALID_CREDENTIALS");
    }

    @Test
    void thieuCsrfBiChan() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", SA_USERNAME, "password", SA_PASSWORD))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        MockHttpSession session = login(SA_USERNAME, SA_PASSWORD);
        mvc.perform(post("/api/v1/auth/logout").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void duLieuDangNhapKhongHopLeTraLoiTungTruong() throws Exception {
        mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
        mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("khong phai json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void chuaDangNhapNhan401Json() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void dangXuatHuyPhien() throws Exception {
        ensureUser("user-dang-xuat", "USER", "0004", "MatKhau@1", "ACTIVE");
        MockHttpSession session = login("user-dang-xuat", "MatKhau@1");
        mvc.perform(post("/api/v1/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/v1/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(countAudit(AuditAction.LOGOUT, "user-dang-xuat")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void doiMatKhau() throws Exception {
        ensureUser("user-doi-mk", "USER", "0005", "MatKhauCu@1", "ACTIVE");
        MockHttpSession session = login("user-doi-mk", "MatKhauCu@1");

        changePassword(session, "sai-mat-khau", "MatKhauMoi@1")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("currentPassword"));
        changePassword(session, "MatKhauCu@1", "ngan")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("newPassword"));
        changePassword(session, "MatKhauCu@1", "MatKhauCu@1")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("newPassword"));
        changePassword(session, "MatKhauCu@1", "a".repeat(73))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("newPassword"));
        changePassword(session, "MatKhauCu@1", "MatKhauMoi@1").andExpect(status().isNoContent());

        assertThat(loginResult("user-doi-mk", "MatKhauCu@1").getResponse().getStatus()).isEqualTo(401);
        assertThat(loginResult("user-doi-mk", "MatKhauMoi@1").getResponse().getStatus()).isEqualTo(200);
        assertThat(countAudit(AuditAction.PASSWORD_CHANGED, "user-doi-mk")).isEqualTo(1);
    }

    private ResultActions changePassword(MockHttpSession s, String current,
                                                                              String next) throws Exception {
        return mvc.perform(put("/api/v1/me/password").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentPassword", current, "newPassword", next))));
    }

    @Test
    void khoaTaiKhoanThiPhienDangMoBiDaRaNgay() throws Exception {
        long id = ensureUser("user-bi-khoa-giua-chung", "USER", "0006", "MatKhau@1", "ACTIVE");
        MockHttpSession session = login("user-bi-khoa-giua-chung", "MatKhau@1");
        mvc.perform(get("/api/v1/me").session(session)).andExpect(status().isOk());
        setStatus(id, "INACTIVE");
        mvc.perform(get("/api/v1/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void doiDonViVaVaiTroCoHieuLucNgayTrongPhienDangMo() throws Exception {
        long id = ensureUser("admin-chuyen-dv", "ADMIN", "0002", "MatKhau@1", "ACTIVE");
        MockHttpSession session = login("admin-chuyen-dv", "MatKhau@1");
        tx().executeWithoutResult(s -> jdbc.update(
                "UPDATE app_user SET unit_id = ?, role = 'USER' WHERE id = ?", unitId("0003"), id));
        mvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unit.code").value("0003"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.adminLevel").doesNotExist());
    }
}
