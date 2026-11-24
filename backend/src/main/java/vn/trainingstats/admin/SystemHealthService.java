package vn.trainingstats.admin;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vn.trainingstats.admin.BackupFiles.Entry;
import vn.trainingstats.common.Times;

/** Tình trạng hệ thống cho Super Admin (M7.3): phiên bản, dữ liệu, ổ đĩa, sao lưu và các cảnh báo cần xử lý. */
@Service
public class SystemHealthService {

    public record Health(String appVersion, String schemaVersion, String javaVersion, String startedAt,
                         String dataDir, long dbSizeBytes, long walSizeBytes, Long dataFreeBytes,
                         String backupDir, Long backupFreeBytes, boolean backupSameDisk,
                         String backupTime, int backupKeep, int backupCount, String lastAutoBackupAt, String lastBackupAt,
                         List<String> warnings) {
    }

    private final JdbcTemplate jdbc;
    private final BackupService backups;
    private final ObjectProvider<BuildProperties> build;
    private final Clock clock;
    private final String dataDir;
    private final String dbFile;

    public SystemHealthService(JdbcTemplate jdbc, BackupService backups, ObjectProvider<BuildProperties> build, Clock clock,
                               @Value("${app.data-dir}") String dataDir, @Value("${app.db-file}") String dbFile) {
        this.jdbc = jdbc;
        this.backups = backups;
        this.build = build;
        this.clock = clock;
        this.dataDir = dataDir;
        this.dbFile = dbFile;
    }

    public Health health() {
        Path data = Path.of(dataDir).toAbsolutePath().normalize();
        Path db = data.resolve(dbFile);
        Path backupDir = backups.dir();
        List<Entry> list = backups.list();
        Instant lastAuto = list.stream().filter(e -> BackupFiles.AUTO.equals(e.kind())).map(Entry::createdAt)
                .findFirst().orElse(null);
        Instant last = list.isEmpty() ? null : list.get(0).createdAt();
        FileStore dataStore = store(data);
        FileStore backupStore = store(backupDir);
        boolean sameDisk = dataStore != null && backupStore != null && Objects.equals(dataStore.name(), backupStore.name())
                && Objects.equals(data.getRoot(), backupDir.getRoot());

        List<String> warnings = new ArrayList<>();
        if (lastAuto == null) {
            warnings.add("Chưa có bản sao lưu tự động nào. Bản đầu tiên được tạo sau " + backups.time() + " hằng ngày.");
        } else if (Duration.between(lastAuto, clock.instant()).toHours() > 48) {
            warnings.add("Bản sao lưu tự động gần nhất đã quá 2 ngày. Kiểm tra dịch vụ và thư mục sao lưu.");
        }
        if (sameDisk) {
            warnings.add("Thư mục sao lưu nằm cùng ổ đĩa với dữ liệu: hỏng ổ đĩa sẽ mất cả hai. Nên đặt thư mục sao lưu "
                    + "ở ổ đĩa khác hoặc ổ mạng.");
        }
        Long backupFree = free(backupStore);
        if (backupFree != null && backupFree < 5L * size(db) + 100L * 1024 * 1024) {
            warnings.add("Ổ đĩa chứa bản sao lưu sắp đầy.");
        }

        BuildProperties b = build.getIfAvailable();
        return new Health(b == null ? "dev" : b.getVersion(), schemaVersion(), System.getProperty("java.version"),
                Times.format(Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime())),
                data.toString(), size(db), size(db.resolveSibling(dbFile + "-wal")), free(dataStore),
                backupDir.toString(), backupFree, sameDisk, backups.time().toString(), backups.keep(), list.size(),
                lastAuto == null ? null : Times.format(lastAuto), last == null ? null : Times.format(last), warnings);
    }

    private String schemaVersion() {
        List<String> v = jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success = 1 "
                + "AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1", String.class);
        return v.isEmpty() ? null : v.get(0);
    }

    private static long size(Path p) {
        try {
            return Files.exists(p) ? Files.size(p) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /** Ổ đĩa của đường dẫn; thư mục chưa có thì lấy thư mục cha gần nhất đang có. */
    private static FileStore store(Path p) {
        Path q = p;
        while (q != null && !Files.exists(q)) {
            q = q.getParent();
        }
        try {
            return q == null ? null : Files.getFileStore(q);
        } catch (IOException e) {
            return null;
        }
    }

    private static Long free(FileStore s) {
        try {
            return s == null ? null : s.getUsableSpace();
        } catch (IOException e) {
            return null;
        }
    }
}
