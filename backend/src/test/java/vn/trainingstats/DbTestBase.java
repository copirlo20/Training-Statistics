package vn.trainingstats;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.sqlite.SQLiteException;

/** Nền chung cho test chạm DB thật: một file SQLite tạm, Flyway chạy toàn bộ migration trong db/migration. */
@SpringBootTest
abstract class DbTestBase {

    private static final Path DATA_DIR = createTempDir();

    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> DATA_DIR.resolve("data").toString());
        // Thư mục cấu hình riêng, trống: không có first-run.properties nên không tạo Super Admin.
        registry.add("app.config-dir", () -> DATA_DIR.resolve("config").toString());
        // Sao lưu tự động chạy theo đồng hồ thật: tắt trong test để không sinh file và audit ngoài ý muốn.
        registry.add("app.backup.auto", () -> "false");
    }

    private static Path createTempDir() {
        return TestDirs.create("test");
    }

    @Autowired
    protected JdbcTemplate jdbc;

    /** Spring không dịch lỗi SQLite, nên lấy mã lỗi gốc từ SQLite, ví dụ SQLITE_CONSTRAINT_CHECK. */
    protected static String loiSqlite(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t instanceof SQLiteException s ? s.getResultCode().name() : t.getClass().getSimpleName();
    }

    protected static String thongBaoGoc(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage());
    }
}
