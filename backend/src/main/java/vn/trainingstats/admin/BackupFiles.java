package vn.trainingstats.admin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import vn.trainingstats.common.Times;

/**
 * Đặt tên, tạo và liệt kê file sao lưu (M7.1). Không phụ thuộc Spring để dùng được cả lúc khởi động, trước khi Flyway
 * chạy (sao lưu trước nâng cấp, M7.2).
 *
 * <p>Tên file: {@code trainingstats-<loại>-yyyyMMdd-HHmmss[-n].db}, giờ Việt Nam. Loại {@code auto} (hằng ngày, xoay
 * vòng giữ N bản), {@code manual} (Super Admin tạo), {@code upgrade} (trước khi chạy migration, nằm trong thư mục con
 * {@code upgrade/}, không bị xoay vòng).
 */
public final class BackupFiles {

    public static final String AUTO = "auto";
    public static final String MANUAL = "manual";
    public static final String UPGRADE = "upgrade";
    public static final String UPGRADE_DIR = "upgrade";

    /** Tên hợp lệ: chặn mọi đường dẫn lạ khi tải về (không cho "..", dấu gạch chéo). */
    static final Pattern NAME = Pattern.compile("trainingstats-(auto|manual|upgrade)-(\\d{8})-(\\d{6})(?:-\\d+)?(?:-v[0-9.]+)?\\.db");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(Times.DISPLAY_ZONE);

    public record Entry(String name, String kind, Instant createdAt, long sizeBytes) {
    }

    private BackupFiles() {
    }

    /** Thư mục sao lưu: cấu hình {@code backup.dir} nếu có, không thì {@code <thư mục dữ liệu>/../backups}. */
    public static Path resolveDir(String configured, String dataDir) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        return Path.of(dataDir).toAbsolutePath().normalize().resolveSibling("backups");
    }

    static Path folderOf(Path dir, String kind) {
        return UPGRADE.equals(kind) ? dir.resolve(UPGRADE_DIR) : dir;
    }

    /** Tên file mới chưa trùng; {@code suffix} thêm sau thời điểm (ví dụ "-v1" cho bản trước nâng cấp). */
    static Path newFile(Path dir, String kind, Instant now, String suffix) throws IOException {
        Path folder = folderOf(dir, kind);
        Files.createDirectories(folder);
        String base = "trainingstats-" + kind + "-" + STAMP.format(now);
        for (int n = 0; ; n++) {
            Path p = folder.resolve(base + (n == 0 ? "" : "-" + n) + (suffix == null ? "" : suffix) + ".db");
            if (!Files.exists(p)) {
                return p;
            }
        }
    }

    /**
     * Bản sao nhất quán bằng {@code VACUUM INTO} (chạy được khi ứng dụng đang hoạt động, kể cả trên kết nối chỉ đọc).
     * Ghi ra file tạm rồi đổi tên, để bản sao lỗi giữa chừng không bị nhận nhầm là bản sao lưu.
     */
    static void vacuumInto(Connection c, Path target) throws IOException, SQLException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.deleteIfExists(part);
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(true);
        try (Statement st = c.createStatement()) {
            st.execute("VACUUM INTO '" + part.toString().replace("'", "''") + "'");
        } finally {
            c.setAutoCommit(auto);
        }
        try {
            verify(part);
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Mở bản sao bằng sqlite-jdbc và chạy {@code PRAGMA integrity_check}: bản hỏng thì không giữ. */
    static void verify(Path file) throws IOException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toString().replace('\\', '/') + "?open_mode=1");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
            String result = rs.next() ? rs.getString(1) : null;
            if (!"ok".equalsIgnoreCase(result)) {
                throw new IOException("Bản sao lưu không toàn vẹn: " + result);
            }
        } catch (SQLException e) {
            Files.deleteIfExists(file);
            throw new IOException("Không mở được bản sao lưu vừa tạo: " + e.getMessage(), e);
        }
    }

    /** Mọi bản sao lưu (cả thư mục con upgrade/), mới nhất trước. */
    static List<Entry> list(Path dir) throws IOException {
        List<Entry> out = new ArrayList<>();
        for (Path folder : List.of(dir, dir.resolve(UPGRADE_DIR))) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (Stream<Path> files = Files.list(folder)) {
                for (Path p : files.toList()) {
                    Matcher m = NAME.matcher(p.getFileName().toString());
                    boolean inUpgrade = folder.endsWith(UPGRADE_DIR) && !folder.equals(dir);
                    if (!m.matches() || UPGRADE.equals(m.group(1)) != inUpgrade || !Files.isRegularFile(p)) {
                        continue;
                    }
                    out.add(new Entry(p.getFileName().toString(), m.group(1), Files.getLastModifiedTime(p).toInstant(),
                            Files.size(p)));
                }
            }
        }
        out.sort(Comparator.comparing(Entry::createdAt).reversed().thenComparing(Entry::name, Comparator.reverseOrder()));
        return out;
    }

    /** Đường dẫn file theo tên, chỉ khi tên đúng mẫu và file có thật. */
    static Optional<Path> find(Path dir, String name) {
        if (name == null) {
            return Optional.empty();
        }
        Matcher m = NAME.matcher(name);
        if (!m.matches()) {
            return Optional.empty();
        }
        Path p = folderOf(dir, m.group(1)).resolve(name);
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }
}
