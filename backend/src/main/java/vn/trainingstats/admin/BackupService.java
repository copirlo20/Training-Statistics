package vn.trainingstats.admin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.trainingstats.admin.BackupFiles.Entry;
import vn.trainingstats.audit.AuditAction;
import vn.trainingstats.audit.AuditService;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.Times;
import vn.trainingstats.common.Validation;
import vn.trainingstats.settings.AppSettings;

/**
 * Sao lưu (M7.1, ARCHITECTURE mục 6): bản sao nhất quán bằng {@code VACUUM INTO} trên kết nối chỉ đọc (không cần
 * giao dịch ghi, không chặn người đang làm việc). Bản tự động xoay vòng giữ {@code backup.keep} bản gần nhất; bản thủ
 * công và bản trước nâng cấp không bị xóa tự động.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    public static final LocalTime DEFAULT_TIME = LocalTime.of(2, 0);
    public static final int MAX_KEEP = 30;

    public record Settings(String time, int keep, String dir, String effectiveDir, String defaultDir) {
    }

    public record SettingsRequest(String time, Integer keep, String dir) {
    }

    private final DataSource readDataSource;
    private final JdbcTemplate jdbc;
    private final AppSettings settings;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String dataDir;

    public BackupService(@Qualifier("readDataSource") DataSource readDataSource, JdbcTemplate jdbc, AppSettings settings,
                         AuditService audit, PlatformTransactionManager txManager, Clock clock,
                         @Value("${app.data-dir}") String dataDir) {
        this.readDataSource = readDataSource;
        this.jdbc = jdbc;
        this.settings = settings;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.dataDir = dataDir;
    }

    public Path dir() {
        return BackupFiles.resolveDir(settings.get(AppSettings.BACKUP_DIR, ""), dataDir);
    }

    public Path defaultDir() {
        return BackupFiles.resolveDir(null, dataDir);
    }

    public LocalTime time() {
        try {
            return LocalTime.parse(settings.get(AppSettings.BACKUP_TIME, "02:00").trim());
        } catch (DateTimeParseException e) {
            return DEFAULT_TIME;
        }
    }

    public int keep() {
        return Math.min(MAX_KEEP, Math.max(1, settings.getInt(AppSettings.BACKUP_KEEP, 5)));
    }

    public List<Entry> list() {
        try {
            return BackupFiles.list(dir());
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc được thư mục sao lưu " + dir() + ": " + e.getMessage(), e);
        }
    }

    public Optional<Instant> lastAuto() {
        return list().stream().filter(e -> BackupFiles.AUTO.equals(e.kind())).map(Entry::createdAt).findFirst();
    }

    /** Một lần sao lưu tại một thời điểm: hai yêu cầu cùng lúc không ghi chồng nhau. */
    synchronized Entry create(String kind) {
        Path dir = dir();
        try {
            Path target = BackupFiles.newFile(dir, kind, clock.instant(), null);
            try (Connection c = readDataSource.getConnection()) {
                BackupFiles.vacuumInto(c, target);
            }
            return new Entry(target.getFileName().toString(), kind, Files.getLastModifiedTime(target).toInstant(),
                    Files.size(target));
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Không tạo được bản sao lưu trong " + dir + ": " + e.getMessage(), e);
        }
    }

    /** Super Admin tạo bản sao lưu thủ công (ghi audit). */
    public Entry createManual() {
        Entry e;
        try {
            e = create(BackupFiles.MANUAL);
        } catch (IllegalStateException ex) {
            audit.record(AuditAction.BACKUP_FAILED, "backup", null, null, null, Map.of("error", ex.getMessage()));
            throw ApiException.badRequest("BACKUP_FAILED", ex.getMessage());
        }
        audit.record(AuditAction.BACKUP_CREATED, "backup", null, null, null, describe(e));
        return e;
    }

    /** Sao lưu tự động hằng ngày rồi xóa bản tự động cũ hơn số bản cần giữ. */
    public Optional<Entry> runAuto() {
        try {
            Entry e = create(BackupFiles.AUTO);
            List<String> removed = rotate();
            Map<String, Object> after = describe(e);
            after.put("removed", removed);
            audit.recordAs(AuditService.Actor.SYSTEM, AuditAction.BACKUP_AUTO_CREATED, "backup", null, null, null, after);
            log.info("Da sao luu tu dong: {} (xoa {} ban cu)", e.name(), removed.size());
            return Optional.of(e);
        } catch (RuntimeException ex) {
            log.error("Sao luu tu dong that bai", ex);
            audit.recordAs(AuditService.Actor.SYSTEM, AuditAction.BACKUP_FAILED, "backup", null, null, null,
                    Map.of("error", String.valueOf(ex.getMessage())));
            return Optional.empty();
        }
    }

    /** Xóa bản tự động cũ, chỉ giữ {@code keep()} bản mới nhất. Trả tên các file đã xóa. */
    List<String> rotate() {
        List<Entry> autos = list().stream().filter(e -> BackupFiles.AUTO.equals(e.kind())).toList();
        List<String> removed = new ArrayList<>();
        for (Entry e : autos.subList(Math.min(keep(), autos.size()), autos.size())) {
            try {
                if (Files.deleteIfExists(dir().resolve(e.name()))) {
                    removed.add(e.name());
                }
            } catch (IOException ex) {
                log.warn("Khong xoa duoc ban sao luu cu {}: {}", e.name(), ex.getMessage());
            }
        }
        return removed;
    }

    /** File để tải về; tên lạ hoặc không có thì 404. Ghi audit vì bản sao lưu chứa toàn bộ dữ liệu. */
    public Path fileForDownload(String name) {
        Path p = BackupFiles.find(dir(), name).orElseThrow(() -> ApiException.notFound("Không tìm thấy bản sao lưu."));
        audit.record(AuditAction.BACKUP_DOWNLOADED, "backup", null, null, null, Map.of("name", name));
        return p;
    }

    public Settings settings() {
        return new Settings(time().toString(), keep(), settings.get(AppSettings.BACKUP_DIR, ""), dir().toString(),
                defaultDir().toString());
    }

    /** Đổi giờ sao lưu, số bản giữ, thư mục (rỗng là mặc định). Thư mục phải tạo và ghi được ngay. */
    public Settings update(SettingsRequest r) {
        var v = new Validation();
        String time = r.time() == null ? "" : r.time().trim();
        try {
            time = LocalTime.parse(time, DateTimeFormatter.ofPattern("H:mm")).toString();
        } catch (DateTimeParseException e) {
            v.add("time", "Giờ sao lưu dạng HH:mm, ví dụ 02:00.");
        }
        if (r.keep() == null || r.keep() < 1 || r.keep() > MAX_KEEP) {
            v.add("keep", "Số bản giữ lại từ 1 đến " + MAX_KEEP + ".");
        }
        String dir = r.dir() == null ? "" : r.dir().trim();
        if (!dir.isEmpty()) {
            Path p = Path.of(dir);
            if (!p.isAbsolute()) {
                v.add("dir", "Nhập đường dẫn đầy đủ, ví dụ D:\\SaoLuu\\TrainingStats.");
            } else {
                try {
                    Files.createDirectories(p);
                    Path probe = Files.createTempFile(p, "ghi-thu-", ".tmp");
                    Files.delete(probe);
                } catch (IOException | RuntimeException e) {
                    v.add("dir", "Không tạo hoặc ghi được vào thư mục này: " + e.getMessage());
                }
            }
        }
        v.throwIfAny();
        Settings before = settings();
        String newTime = time;
        int keep = r.keep();
        tx.executeWithoutResult(s -> {
            put(AppSettings.BACKUP_TIME, newTime);
            put(AppSettings.BACKUP_KEEP, String.valueOf(keep));
            put(AppSettings.BACKUP_DIR, dir);
            audit.record(AuditAction.SETTINGS_UPDATED, "app_setting", null, null, before,
                    Map.of("time", newTime, "keep", keep, "dir", dir));
        });
        return settings();
    }

    private void put(String key, String value) {
        int n = jdbc.update("UPDATE app_setting SET value = ?, updated_at = ? WHERE key = ?", value, Times.now(clock), key);
        if (n == 0) {
            jdbc.update("INSERT INTO app_setting (key, value) VALUES (?, ?)", key, value);
        }
    }

    /** Đến lượt sao lưu tự động: đã qua giờ hẹn của hôm nay (giờ Việt Nam) mà hôm nay chưa có bản tự động. */
    public static boolean due(Instant now, LocalTime at, Optional<Instant> lastAuto) {
        var local = now.atZone(Times.DISPLAY_ZONE);
        if (local.toLocalTime().isBefore(at)) {
            return false;
        }
        LocalDate today = local.toLocalDate();
        return lastAuto.map(t -> t.atZone(Times.DISPLAY_ZONE).toLocalDate().isBefore(today)).orElse(true);
    }

    public boolean autoDue() {
        return due(clock.instant(), time(), lastAuto());
    }

    private static Map<String, Object> describe(Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.name());
        m.put("kind", e.kind());
        m.put("sizeBytes", e.sizeBytes());
        return m;
    }
}
