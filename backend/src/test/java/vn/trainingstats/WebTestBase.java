package vn.trainingstats;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.trainingstats.admin.FirstRunInitializer;

/**
 * Nền cho test API: chạy server thật trên cổng ngẫu nhiên, DB tạm riêng, và có sẵn first-run.properties như bộ cài
 * tạo ra. Khi khởi động, ứng dụng tạo Super Admin {@value #SA_USERNAME} từ file đó (chính là việc M1.6 cần kiểm).
 *
 * Dữ liệu test ở đây phải được COMMIT (không dùng @Transactional ở lớp test), vì bộ lọc nạp lại người dùng chạy trên
 * kết nối đọc riêng và chỉ thấy dữ liệu đã commit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
abstract class WebTestBase {

    static final String SA_USERNAME = "admin";
    static final String SA_PASSWORD = "KhoiTao@2026";

    static final Path ROOT = createRoot();
    static final Path CONFIG_DIR = ROOT.resolve("config");
    static final Path FIRST_RUN_FILE = CONFIG_DIR.resolve(FirstRunInitializer.FILE_NAME);

    private static Path createRoot() {
        try {
            Path root = TestDirs.create("web");
            Path config = Files.createDirectories(root.resolve("config"));
            Files.writeString(config.resolve(FirstRunInitializer.FILE_NAME),
                    FirstRunInitializer.KEY_PASSWORD + "=" + SA_PASSWORD + "\n", StandardCharsets.UTF_8);
            return root;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> ROOT.resolve("data").toString());
        registry.add("app.config-dir", CONFIG_DIR::toString);
        registry.add("app.backup.auto", () -> "false");
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected PasswordEncoder encoder;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    private PlatformTransactionManager txManager;
    @LocalServerPort
    protected int port;

    protected TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    protected long unitId(String code) {
        return jdbc.queryForObject("SELECT id FROM unit WHERE code = ?", Long.class, code);
    }

    /**
     * Tạo (hoặc đưa về trạng thái mong muốn) một người dùng có tài khoản, đã commit.
     * {@code unitCode} null cho Super Admin.
     */
    protected long ensureUser(String username, String role, String unitCode, String password, String status) {
        String hash = password == null ? null : encoder.encode(password);
        return tx().execute(s -> {
            Long unit = unitCode == null ? null : unitId(unitCode);
            String deletedAt = "DELETED".equals(status) ? "2026-01-01T00:00:00.000Z" : null;
            List<Long> ids = jdbc.queryForList("SELECT id FROM app_user WHERE username = ?", Long.class, username);
            if (ids.isEmpty()) {
                jdbc.update("""
                        INSERT INTO app_user (kind, last_name, first_name, search_text, unit_id, role, status, username,
                                              password_hash, personal_code, deleted_at)
                        VALUES ('PERSON', 'Test', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, username, "test " + username, unit, role, status, username, hash, "T-" + username,
                        deletedAt);
                return jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
            }
            jdbc.update("""
                    UPDATE app_user SET unit_id = ?, role = ?, status = ?, password_hash = ?, deleted_at = ?
                    WHERE id = ?
                    """, unit, role, status, hash, deletedAt, ids.get(0));
            return ids.get(0);
        });
    }

    protected void setStatus(long userId, String status) {
        String deletedAt = "DELETED".equals(status) ? "2026-01-01T00:00:00.000Z" : null;
        tx().executeWithoutResult(s -> jdbc.update(
                "UPDATE app_user SET status = ?, deleted_at = ? WHERE id = ?", status, deletedAt, userId));
    }

    protected MvcResult loginResult(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andReturn();
    }

    /** Đăng nhập và trả phiên để dùng ở các request sau. Thất bại thì ném lỗi ngay. */
    protected MockHttpSession login(String username, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    protected int countAudit(String action, String actorUsername) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND actor_username = ?",
                Integer.class, action, actorUsername);
        return n == null ? 0 : n;
    }
}
