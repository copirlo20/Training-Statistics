package vn.trainingstats.admin;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vn.trainingstats.common.ApiException;

/**
 * Lần chạy đầu: bộ cài để mật khẩu Super Admin ở {@code <config-dir>/first-run.properties} (dạng văn bản thường,
 * chỉ SYSTEM và Administrators đọc được). Ứng dụng đọc, băm BCrypt, tạo tài khoản rồi <b>xóa file ngay</b>, kể cả
 * khi tạo thất bại, để mật khẩu không nằm lại trên đĩa (docs/OPERATIONS-GUIDE.md phụ lục A, D4).
 */
@Component
@Order(0)
@ConditionalOnProperty(name = "app.first-run.enabled", havingValue = "true", matchIfMissing = true)
public class FirstRunInitializer implements ApplicationRunner {

    public static final String FILE_NAME = "first-run.properties";
    public static final String KEY_PASSWORD = "app.first-run.admin-password";
    public static final String KEY_USERNAME = "app.first-run.admin-username";

    private static final Logger log = LoggerFactory.getLogger(FirstRunInitializer.class);

    private final SuperAdminService superAdmins;
    private final Path configDir;

    public FirstRunInitializer(SuperAdminService superAdmins, @Value("${app.config-dir}") String configDir) {
        this.superAdmins = superAdmins;
        this.configDir = Path.of(configDir).toAbsolutePath().normalize();
    }

    @Override
    public void run(ApplicationArguments args) {
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            if (!superAdmins.anySuperAdmin()) {
                log.warn("Chua co Super Admin va khong co {}. Dung cong cu reset-superadmin de tao tai khoan.", file);
            }
            return;
        }
        try {
            if (superAdmins.anySuperAdmin()) {
                log.warn("Da co Super Admin, bo qua {} va xoa file.", file);
                return;
            }
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            String password = props.getProperty(KEY_PASSWORD);
            String username = props.getProperty(KEY_USERNAME, SuperAdminService.DEFAULT_USERNAME).trim();
            if (password == null || password.isEmpty()) {
                log.error("{} khong co {}. Chua tao Super Admin.", file, KEY_PASSWORD);
                return;
            }
            superAdmins.createInitial(username, password);
            log.info("Da tao Super Admin '{}' tu {}.", username, FILE_NAME);
        } catch (ApiException e) {
            log.error("Khong tao duoc Super Admin tu {}: {}. Dung cong cu reset-superadmin.", FILE_NAME, e.getMessage());
        } catch (IOException e) {
            log.error("Khong doc duoc {}: {}", file, e.getMessage());
        } finally {
            deleteSecret(file);
        }
    }

    private static void deleteSecret(Path file) {
        try {
            // Ghi đè rỗng trước, phòng khi xóa thất bại thì mật khẩu cũng không còn trong file.
            Files.write(file, new byte[0]);
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.error("KHONG XOA DUOC {}: {}. File co the con mat khau, hay xoa thu cong ngay.", file, e.getMessage());
        }
    }
}
