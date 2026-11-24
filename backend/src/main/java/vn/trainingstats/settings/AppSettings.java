package vn.trainingstats.settings;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Đọc cấu hình trong bảng app_setting. Giá trị mặc định dùng khi chưa có dòng hoặc giá trị hỏng. */
@Service
public class AppSettings {

    public static final String PASSWORD_MIN_LENGTH = "password.min-length";
    public static final String SESSION_TIMEOUT_MINUTES = "session.timeout-minutes";
    public static final String BACKUP_TIME = "backup.time";
    public static final String BACKUP_KEEP = "backup.keep";
    public static final String BACKUP_DIR = "backup.dir";

    private final JdbcTemplate jdbc;

    public AppSettings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String get(String key, String defaultValue) {
        List<String> values = jdbc.queryForList("SELECT value FROM app_setting WHERE key = ?", String.class, key);
        return values.isEmpty() ? defaultValue : values.get(0);
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public int passwordMinLength() {
        return Math.max(1, getInt(PASSWORD_MIN_LENGTH, 8));
    }

    public int sessionTimeoutMinutes() {
        return Math.max(1, getInt(SESSION_TIMEOUT_MINUTES, 30));
    }
}
