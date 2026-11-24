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
     * Checksum Flyway của {@code V1__init.sql} trước và sau lần sửa chú thích duy nhất theo yêu cầu chủ dự án
     * (ARCHITECTURE S13). Nội dung SQL không đổi, nhưng bản cài đã chạy V1 trước đó mang checksum cũ nên Flyway từ chối
     * khởi động ("checksum mismatch"), dịch vụ dừng ngay sau khi cài bản mới.
     */
    public static final int V1_CHECKSUM_BEFORE_COMMENT_EDIT = -669641724;
    public static final int V1_CHECKSUM = 2136348535;
    private static final String V1_RESOURCE = "db/migration/V1__init.sql";

    /** Trả đường dẫn bản sao lưu đã tạo (rỗng khi DB mới hoặc không có gì để nâng cấp). */
    public static Optional<Path> migrate(Flyway flyway, String dataDir, Clock clock) {
        alignV1Checksum(flyway);
        MigrationInfoService info = flyway.info();
        MigrationInfo current = info.current();
        Path backup = null;
        if (current != null && info.pending().length > 0) {
            String version = current.getVersion() == null ? "0" : current.getVersion().getVersion();
            DataSource ds = flyway.getConfiguration().getDataSource();
            try (Connection c = ds.getConnection()) {
                Path dir = BackupFiles.resolveDir(configuredDir(c), dataDir);
                backup = BackupFiles.newFile(dir, BackupFiles.UPGRADE, clock.instant(), "-v" + version);
                BackupFiles.vacuumInto(c, backup);
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
     * Đổi checksum đã lưu của V1 từ giá trị trước lần sửa chú thích sang giá trị hiện tại, và chỉ làm vậy khi file V1
     * đi kèm đúng là bản đã sửa. Không dùng {@code flyway.repair()}: lệnh đó chấp nhận mọi chênh lệch, kể cả file
     * migration bị sửa thật; ở đây mọi chênh lệch khác vẫn bị Flyway chặn như thường.
     */
    static void alignV1Checksum(Flyway flyway) {
        Integer shipped = resourceChecksum(V1_RESOURCE);
        if (shipped == null || shipped != V1_CHECKSUM) {
            return;
        }
        DataSource ds = flyway.getConfiguration().getDataSource();
        try (Connection c = ds.getConnection()) {
            if (!hasHistoryTable(c)) {
                return;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE flyway_schema_history SET checksum = ? WHERE version = '1' AND checksum = ?")) {
                ps.setInt(1, V1_CHECKSUM);
                ps.setInt(2, V1_CHECKSUM_BEFORE_COMMENT_EDIT);
                if (ps.executeUpdate() > 0) {
                    log.info("Da cap nhat checksum cua migration V1 (chi doi chu thich): {} -> {}",
                            V1_CHECKSUM_BEFORE_COMMENT_EDIT, V1_CHECKSUM);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Khong cap nhat duoc checksum migration V1: " + e.getMessage(), e);
        }
    }

    private static boolean hasHistoryTable(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'flyway_schema_history'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
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
