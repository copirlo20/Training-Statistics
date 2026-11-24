package vn.trainingstats.admin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Optional;
import java.util.zip.CRC32;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.trainingstats.settings.AppSettings;

/**
 * Nâng cấp an toàn (M7.2, ARCHITECTURE mục 6): khi khởi động, nếu DB đã có dữ liệu và còn migration chưa chạy thì sao
 * lưu DB vào {@code <thư mục sao lưu>/upgrade/} trước, rồi mới chạy Flyway. Sao lưu lỗi thì không nâng cấp. Migration
 * lỗi thì ứng dụng không khởi động, bản sao lưu được giữ (không bị vòng xoay xóa) để khôi phục.
 */
@Configuration
public class UpgradeBackup {

    private static final Logger log = LoggerFactory.getLogger(UpgradeBackup.class);

    @Bean
    FlywayMigrationStrategy flywayMigrationStrategy(@Value("${app.data-dir}") String dataDir, Clock clock) {
        return flyway -> migrate(flyway, dataDir, clock);
    }

    /**
     * Checksum Flyway của các bản {@code V1__init.sql} đã phát hành, theo yêu cầu chủ dự án sửa thẳng V1 thay vì thêm
     * migration (ARCHITECTURE S13): bản đầu, bản chỉ đổi chú thích, và bản hiện tại thêm cột
     * {@code app_user.must_change_password}. Bản cài đã chạy V1 cũ mang checksum cũ nên Flyway thường từ chối khởi
     * động ("checksum mismatch"); {@link #alignV1} đưa DB đó về đúng bản hiện tại.
     */
    public static final int V1_CHECKSUM_BEFORE_COMMENT_EDIT = -669641724;
    public static final int V1_CHECKSUM_BEFORE_MUST_CHANGE_PASSWORD = 2136348535;
    public static final int V1_CHECKSUM = -527056567;
    private static final String V1_RESOURCE = "db/migration/V1__init.sql";

    /** Trả đường dẫn bản sao lưu đã tạo (rỗng khi DB mới hoặc không có gì để nâng cấp). */
    public static Optional<Path> migrate(Flyway flyway, String dataDir, Clock clock) {
        Path backup = alignV1(flyway, dataDir, clock);
        MigrationInfoService info = flyway.info();
        MigrationInfo current = info.current();
        if (current != null && info.pending().length > 0) {
            String version = current.getVersion() == null ? "0" : current.getVersion().getVersion();
            DataSource ds = flyway.getConfiguration().getDataSource();
            try (Connection c = ds.getConnection()) {
                backup = backup(c, dataDir, clock, "-v" + version);
            } catch (IOException | SQLException e) {
                throw new IllegalStateException("Khong sao luu duoc truoc khi nang cap, dung lai (chua doi du lieu): "
                        + e.getMessage(), e);
            }
            log.info("Da sao luu truoc khi nang cap (phien ban du lieu {}): {}", version, backup);
        }
        try {
            flyway.migrate();
        } catch (RuntimeException e) {
            if (backup != null) {
                log.error("Nang cap du lieu that bai. Ban sao luu truoc nang cap: {}", backup);
            }
            throw e;
        }
        return Optional.ofNullable(backup);
    }

    /**
     * Đưa DB đã chạy một bản V1 cũ về đúng bản V1 hiện tại, chỉ khi file V1 đi kèm đúng là bản hiện tại và checksum
     * đã lưu là một bản cũ đã phát hành. Thiếu cột {@code must_change_password} thì sao lưu trước, rồi thêm cột và bật cờ
     * cho tài khoản đã cấp mà chưa từng đăng nhập (Super Admin tạo lúc cài có {@code created_by} NULL nên không bị bắt),
     * cùng một giao dịch với việc đổi checksum. Không dùng {@code flyway.repair()}: lệnh đó chấp nhận mọi chênh lệch,
     * kể cả file migration bị sửa thật; ở đây mọi chênh lệch khác vẫn bị Flyway chặn như thường.
     *
     * @return bản sao lưu nếu đã đổi schema
     */
    static Path alignV1(Flyway flyway, String dataDir, Clock clock) {
        Integer shipped = resourceChecksum(V1_RESOURCE);
        if (shipped == null || shipped != V1_CHECKSUM) {
            return null;
        }
        DataSource ds = flyway.getConfiguration().getDataSource();
        try (Connection c = ds.getConnection()) {
            Integer stored = hasTable(c, "flyway_schema_history") ? storedV1Checksum(c) : null;
            if (stored == null || (stored != V1_CHECKSUM_BEFORE_COMMENT_EDIT
                    && stored != V1_CHECKSUM_BEFORE_MUST_CHANGE_PASSWORD)) {
                return null;
            }
            boolean addColumn = !hasColumn(c, "app_user", "must_change_password");
            Path backup = addColumn ? backup(c, dataDir, clock, "-v1") : null;
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                if (addColumn) {
                    st.executeUpdate("ALTER TABLE app_user ADD COLUMN must_change_password INTEGER NOT NULL DEFAULT 0 "
                            + "CHECK (must_change_password IN (0, 1))");
                    st.executeUpdate("UPDATE app_user SET must_change_password = 1 WHERE password_hash IS NOT NULL "
                            + "AND last_login_at IS NULL AND created_by IS NOT NULL");
                }
                st.executeUpdate("UPDATE flyway_schema_history SET checksum = " + V1_CHECKSUM
                        + " WHERE version = '1' AND checksum = " + stored);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
            log.info("Da cap nhat migration V1: checksum {} -> {}{}", stored, V1_CHECKSUM,
                    addColumn ? ", them cot app_user.must_change_password, sao luu truoc: " + backup : "");
            return backup;
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Khong cap nhat duoc migration V1, dung lai: " + e.getMessage(), e);
        }
    }

    private static Path backup(Connection c, String dataDir, Clock clock, String suffix)
            throws IOException, SQLException {
        Path dir = BackupFiles.resolveDir(configuredDir(c), dataDir);
        Path file = BackupFiles.newFile(dir, BackupFiles.UPGRADE, clock.instant(), suffix);
        BackupFiles.vacuumInto(c, file);
        return file;
    }

    private static Integer storedV1Checksum(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT checksum FROM flyway_schema_history WHERE version = '1' AND success = 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : null;
        }
    }

    private static boolean hasTable(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM pragma_table_info(?) WHERE name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Checksum theo cách của Flyway: CRC32 trên từng dòng UTF-8 (bỏ ký tự xuống dòng và BOM đầu file). */
    public static Integer resourceChecksum(String resource) {
        InputStream in = UpgradeBackup.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            return null;
        }
        CRC32 crc = new CRC32();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line != null && line.startsWith("﻿")) {
                line = line.substring(1);
            }
            while (line != null) {
                crc.update(line.getBytes(StandardCharsets.UTF_8));
                line = reader.readLine();
            }
        } catch (IOException e) {
            return null;
        }
        return (int) crc.getValue();
    }

    private static String configuredDir(Connection c) {
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM app_setting WHERE key = ?")) {
            ps.setString(1, AppSettings.BACKUP_DIR);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }
}
