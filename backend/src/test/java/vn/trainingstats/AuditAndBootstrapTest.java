package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import vn.trainingstats.admin.FirstRunInitializer;
import vn.trainingstats.admin.SuperAdminService;
import vn.trainingstats.audit.AuditAction;
import vn.trainingstats.audit.AuditService;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.Times;

/** M1.5 (audit log) và M1.6 (khởi tạo Super Admin lần đầu, công cụ đặt lại Super Admin). */
class AuditAndBootstrapTest extends WebTestBase {

    @Autowired
    AuditService audit;
    @Autowired
    SuperAdminService superAdmins;
    @Autowired
    FirstRunInitializer firstRun;

    // ------------------------------------------------------------------ M1.6 lần chạy đầu

    @Test
    void lanChayDauTaoSuperAdminTuFileVaXoaFile() {
        assertThat(Files.exists(FIRST_RUN_FILE)).as("file chứa mật khẩu phải bị xóa ngay").isFalse();
        Map<String, Object> sa = jdbc.queryForMap(
                "SELECT role, status, unit_id, password_hash FROM app_user WHERE username = ?", SA_USERNAME);
        assertThat(sa.get("role")).isEqualTo("SUPER_ADMIN");
        assertThat(sa.get("status")).isEqualTo("ACTIVE");
        assertThat(sa.get("unit_id")).isNull();
        String hash = (String) sa.get("password_hash");
        assertThat(hash).startsWith("$2").isNotEqualTo(SA_PASSWORD);
        assertThat(encoder.matches(SA_PASSWORD, hash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ?", Integer.class,
                AuditAction.SUPER_ADMIN_CREATED)).isGreaterThanOrEqualTo(1);
        // Mật khẩu không được nằm ở bất kỳ đâu trong nhật ký.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE after_json LIKE ? OR before_json LIKE ?",
                Integer.class, "%" + SA_PASSWORD + "%", "%" + SA_PASSWORD + "%")).isZero();
    }

    @Test
    void daCoSuperAdminThiFileMoiBiBoQuaVaXoa() throws Exception {
        Files.writeString(FIRST_RUN_FILE, FirstRunInitializer.KEY_PASSWORD + "=MatKhauKhac@1\n"
                + FirstRunInitializer.KEY_USERNAME + "=admin-thu-hai\n");
        firstRun.run(null);
        assertThat(Files.exists(FIRST_RUN_FILE)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username = 'admin-thu-hai'", Integer.class))
                .isZero();
    }

    // ------------------------------------------------------------------ M1.6 đặt lại Super Admin

    @Test
    void datLaiSuperAdminTaoMoiHoacMoKhoaVaDoiMatKhau() throws Exception {
        assertThat(superAdmins.reset("sa-du-phong", "DuPhong@2026")).isEqualTo(SuperAdminService.ResetOutcome.CREATED);
        login("sa-du-phong", "DuPhong@2026");

        long id = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'sa-du-phong'", Long.class);
        setStatus(id, "INACTIVE");
        assertThat(superAdmins.reset("sa-du-phong", "MoiHon@2026")).isEqualTo(SuperAdminService.ResetOutcome.RESET);
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, id)).isEqualTo("ACTIVE");
        assertThat(loginResult("sa-du-phong", "DuPhong@2026").getResponse().getStatus()).isEqualTo(401);
        login("sa-du-phong", "MoiHon@2026");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_id = ?",
                Integer.class, AuditAction.SUPER_ADMIN_RESET, id)).isEqualTo(1);
    }

    @Test
    void datLaiKhongNangQuyenTaiKhoanKhacVaKiemTraMatKhau() {
        ensureUser("admin-khong-phai-sa", "ADMIN", "0002", "MatKhau@1", "ACTIVE");
        assertThatThrownBy(() -> superAdmins.reset("admin-khong-phai-sa", "MatKhau@2026"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_SUPER_ADMIN"));
        assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE username = 'admin-khong-phai-sa'", String.class))
                .isEqualTo("ADMIN");
        assertThatThrownBy(() -> superAdmins.reset("sa-mk-ngan", "ngan")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> superAdmins.reset("ten co dau cach", "MatKhau@2026")).isInstanceOf(ApiException.class);
    }

    @Test
    void congCuDongLenhChayKhongCanWebVaBaoLoiKhiKhongCoMatKhau() throws Exception {
        Path dir = TestDirs.create("cli");
        PrintStream originalErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = TrainingStatsApplication.runResetTool(new String[] {
                    "--reset-superadmin",
                    "--app.data-dir=" + dir.resolve("data"),
                    "--app.config-dir=" + dir.resolve("config")});
        } finally {
            System.setErr(originalErr);
        }
        assertThat(code).isEqualTo(1);
        // Đi tới bước hỏi mật khẩu nghĩa là context không web đã khởi động được và Flyway đã tạo DB.
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("TS_RESET_PASSWORD");
        assertThat(Files.exists(dir.resolve("data").resolve("trainingstats.db"))).isTrue();
    }

    // ------------------------------------------------------------------ M1.5 audit log

    @Test
    void xemNhatKyLocTheoHanhDongVaPhanTrang() throws Exception {
        MockHttpSession sa = login(SA_USERNAME, SA_PASSWORD);
        mvc.perform(get("/api/v1/audit-logs").param("action", AuditAction.LOGIN_SUCCESS).param("size", "1").session(sa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].action").value(AuditAction.LOGIN_SUCCESS))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.total").isNumber());
        mvc.perform(get("/api/v1/audit-logs").param("actor", SA_USERNAME).session(sa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].actorUsername").value(SA_USERNAME));
    }

    @Test
    void locTheoNgayGioVietNam() throws Exception {
        MockHttpSession sa = login(SA_USERNAME, SA_PASSWORD);
        String today = LocalDate.now(Times.DISPLAY_ZONE).toString();
        mvc.perform(get("/api/v1/audit-logs").param("from", today).param("to", today).session(sa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(Matchers.greaterThan(0)));
        mvc.perform(get("/api/v1/audit-logs").param("from", "2000-01-01").param("to", "2000-01-02").session(sa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/audit-logs").param("from", "18/11/2026").session(sa))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/audit-logs").param("from", "2026-11-18").param("to", "2026-11-01").session(sa))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nhatKyAnTruongBiMat() {
        audit.recordAs(new AuditService.Actor(null, "test-redact"), "TEST_REDACT", "app_user", 1L, "0002",
                Map.of("password", "mk-truoc"),
                Map.of("name", "Nguyễn Văn A", "passwordHash", "$2a$hash", "nested",
                        Map.of("resetToken", "tok"), "list", List.of(Map.of("secretKey", "k"))));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT before_json, after_json, unit_code FROM audit_log WHERE action = 'TEST_REDACT'");
        String all = row.get("before_json") + " " + row.get("after_json");
        assertThat(all).doesNotContain("mk-truoc").doesNotContain("$2a$hash").doesNotContain("tok").doesNotContain("\"k\"");
        assertThat(all).contains("***").contains("Nguyễn Văn A");
        assertThat(row.get("unit_code")).isEqualTo("0002");
    }

    @Test
    void nhatKyKhongSuaKhongXoaDuoc() throws Exception {
        MockHttpSession sa = login(SA_USERNAME, SA_PASSWORD);
        // Không có API sửa hay xóa.
        int del = mvc.perform(delete("/api/v1/audit-logs/1").with(csrf()).session(sa)).andReturn().getResponse().getStatus();
        int upd = mvc.perform(put("/api/v1/audit-logs/1").with(csrf()).session(sa)).andReturn().getResponse().getStatus();
        assertThat(del).isIn(404, 405);
        assertThat(upd).isIn(404, 405);
        // Và DB cũng chặn, kể cả trong giao dịch ghi.
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> jdbc.update("UPDATE audit_log SET action = 'X'")))
                .hasMessageContaining("Nhat ky chi duoc them");
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> jdbc.update("DELETE FROM audit_log")))
                .hasMessageContaining("Nhat ky chi duoc them");
    }
}
