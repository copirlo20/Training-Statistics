package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import vn.trainingstats.security.PasswordChangeGate;
import vn.trainingstats.user.UserService;

/**
 * Bắt đổi mật khẩu ở lần đăng nhập đầu (PRD #17): mật khẩu do người khác đặt thì chỉ được xem /me, đổi mật khẩu và
 * đăng xuất cho tới khi tự đổi; máy chủ chặn, không chỉ giao diện.
 */
class MustChangePasswordTest extends WebTestBase {

    private static final String DEFAULT_PW = UserService.DEFAULT_PASSWORD;

    private ResultActions changePassword(MockHttpSession s, String current, String next) throws Exception {
        return mvc.perform(put("/api/v1/me/password").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentPassword", current, "newPassword", next))));
    }

    private long createWithAccount(MockHttpSession admin, String username) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("category", "SQ");
        body.put("lastName", "Phạm Văn");
        body.put("firstName", "Lần Đầu");
        body.put("unitId", unitId("0004"));
        body.put("role", "USER");
        body.put("username", username);
        MvcResult r = mvc.perform(post("/api/v1/users").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    private int flag(long userId) {
        return jdbc.queryForObject("SELECT must_change_password FROM app_user WHERE id = ?", Integer.class, userId);
    }

    @Test
    void taiKhoanMoiPhaiDoiMatKhauTruocKhiDungChucNangKhac() throws Exception {
        MockHttpSession sa = login(SA_USERNAME, SA_PASSWORD);
        mvc.perform(get("/api/v1/me").session(sa)).andExpect(jsonPath("$.mustChangePassword").value(false));

        String name = "lan-dau-" + System.nanoTime();
        long id = createWithAccount(sa, name);
        assertThat(flag(id)).isEqualTo(1);

        MvcResult loginRes = loginResult(name, DEFAULT_PW);
        assertThat(loginRes.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(loginRes.getResponse().getContentAsString()).get("mustChangePassword").asBoolean())
                .isTrue();
        MockHttpSession s = (MockHttpSession) loginRes.getRequest().getSession(false);

        mvc.perform(get("/api/v1/me").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));
        mvc.perform(get("/api/v1/classes").session(s)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(PasswordChangeGate.CODE));
        mvc.perform(get("/api/v1/notifications").session(s)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/users/" + id).session(s)).andExpect(status().isForbidden());

        // Vẫn phải đúng mật khẩu hiện tại và khác mật khẩu cũ.
        changePassword(s, DEFAULT_PW, DEFAULT_PW).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("newPassword"));
        changePassword(s, DEFAULT_PW, "TuDat@2026").andExpect(status().isNoContent());
        assertThat(flag(id)).isZero();

        mvc.perform(get("/api/v1/classes").session(s)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").session(s)).andExpect(jsonPath("$.mustChangePassword").value(false));
        MvcResult again = loginResult(name, "TuDat@2026");
        assertThat(json.readTree(again.getResponse().getContentAsString()).get("mustChangePassword").asBoolean())
                .isFalse();
    }

    @Test
    void datLaiMatKhauThiBatDoiNgayCaPhienDangMoVaVanDangXuatDuoc() throws Exception {
        long id = ensureUser("dat-lai-bat-doi", "USER", "0004", "MatKhau@1", "ACTIVE");
        MockHttpSession user = login("dat-lai-bat-doi", "MatKhau@1");
        mvc.perform(get("/api/v1/classes").session(user)).andExpect(status().isOk());

        MockHttpSession sa = login(SA_USERNAME, SA_PASSWORD);
        mvc.perform(post("/api/v1/users/" + id + "/reset-password").session(sa).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("newPassword", "DatLai@2026"))))
                .andExpect(status().isNoContent());
        assertThat(flag(id)).isEqualTo(1);

        mvc.perform(get("/api/v1/classes").session(user)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(PasswordChangeGate.CODE));
        mvc.perform(post("/api/v1/auth/logout").session(user).with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    void capTaiKhoanBatCoDoiTenGiuNguyenThuHoiBoCo() throws Exception {
        MockHttpSession sa = login(SA_USERNAME, SA_PASSWORD);
        String name = "cap-tk-" + System.nanoTime();
        long id = createWithAccount(sa, name);
        changePassword(login(name, DEFAULT_PW), DEFAULT_PW, "TuDat@2026").andExpect(status().isNoContent());
        assertThat(flag(id)).isZero();

        // Đổi tên giữ mật khẩu chính chủ đã đặt, nên không bắt đổi lại.
        putAccount(sa, id, name + "-moi");
        assertThat(flag(id)).isZero();
        // Thu hồi rồi cấp lại: nhận mật khẩu mặc định, bị bắt đổi.
        putAccount(sa, id, null);
        assertThat(flag(id)).isZero();
        putAccount(sa, id, name + "-lai");
        assertThat(flag(id)).isEqualTo(1);
    }

    private void putAccount(MockHttpSession sa, long id, String username) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        mvc.perform(put("/api/v1/users/" + id + "/account").session(sa).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    @Test
    void superAdminTaoLucCaiDatKhongBiBatDoi() {
        assertThat(jdbc.queryForObject("SELECT must_change_password FROM app_user WHERE username = ?", Integer.class,
                SA_USERNAME)).isZero();
    }
}
