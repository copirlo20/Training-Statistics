package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import vn.trainingstats.admin.UpgradeBackup;

/**
 * M7.2: khi khởi động có migration mới thì sao lưu trước rồi mới chạy Flyway; migration lỗi thì dừng, dữ liệu giữ
 * nguyên phiên bản cũ và bản sao lưu còn đó. DB mới hoặc không có gì để nâng cấp thì không sao lưu.
 */
class UpgradeBackupTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T03:00:00Z"), ZoneOffset.UTC);

    private static Flyway flyway(SQLiteDataSource ds, String... extra) {
        String[] locations = Stream.concat(Stream.of("classpath:db/migration"), Stream.of(extra)).toArray(String[]::new);
        return Flyway.configure().dataSource(ds).locations(locations).load();
    }

    private static String version(Path db) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toString().replace('\\', '/'));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static List<Path> upgradeBackups(Path root) throws Exception {
        Path dir = root.resolve("backups").resolve("upgrade");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".db")).sorted().toList();
        }
    }

    private static void setV1Checksum(Path db, int checksum) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toString().replace('\\', '/'));
             Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE flyway_schema_history SET checksum = " + checksum + " WHERE version = '1'");
        }
    }

    private static int v1Checksum(Path db) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toString().replace('\\', '/'));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT checksum FROM flyway_schema_history WHERE version = '1'")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    @Test
    void fileV1KhongBiSuaThemNgoaiLanDoiChuThichDaGhiNhan() {
        // Sửa V1__init.sql (kể cả chú thích) làm mọi bản cài báo "checksum mismatch" và không khởi động (ARCHITECTURE S13).
        assertThat(UpgradeBackup.resourceChecksum("db/migration/V1__init.sql")).isEqualTo(UpgradeBackup.V1_CHECKSUM);
    }

    @Test
    void banCaiTruocLanDoiChuThichV1VanKhoiDongDuocVaChenhLechKhacVanBiChan() throws Exception {
        Path root = TestDirs.create("upgrade");
        Path dataDir = Files.createDirectories(root.resolve("data"));
        Path db = dataDir.resolve("trainingstats.db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db.toString().replace('\\', '/'));
        UpgradeBackup.migrate(flyway(ds), dataDir.toString(), CLOCK);

        // DB cài trước lần sửa chú thích: Flyway thường từ chối, đúng lỗi dịch vụ dừng ngay sau khi cài bản mới.
        setV1Checksum(db, UpgradeBackup.V1_CHECKSUM_BEFORE_COMMENT_EDIT);
        assertThatThrownBy(() -> flyway(ds).migrate()).hasMessageContaining("checksum mismatch");
        // Khởi động qua UpgradeBackup: checksum được cập nhật, không sao lưu (không có migration mới), dữ liệu giữ nguyên.
        assertThat(UpgradeBackup.migrate(flyway(ds), dataDir.toString(), CLOCK)).isEmpty();
        assertThat(v1Checksum(db)).isEqualTo(UpgradeBackup.V1_CHECKSUM);
        assertThat(version(db)).isEqualTo("1");

        // Chênh lệch khác (file migration bị sửa thật) vẫn bị chặn như thường.
        setV1Checksum(db, 12345);
        assertThatThrownBy(() -> UpgradeBackup.migrate(flyway(ds), dataDir.toString(), CLOCK))
                .hasMessageContaining("checksum mismatch");
        assertThat(v1Checksum(db)).isEqualTo(12345);
    }

    @Test
    void banCaiV1CuDuocThemCotBatDoiMatKhauSauKhiSaoLuu() throws Exception {
        for (int oldChecksum : new int[] {UpgradeBackup.V1_CHECKSUM_BEFORE_MUST_CHANGE_PASSWORD,
                UpgradeBackup.V1_CHECKSUM_BEFORE_COMMENT_EDIT}) {
            Path root = TestDirs.create("upgrade");
            Path dataDir = Files.createDirectories(root.resolve("data"));
            Path db = dataDir.resolve("trainingstats.db");
            SQLiteDataSource ds = new SQLiteDataSource();
            ds.setUrl("jdbc:sqlite:" + db.toString().replace('\\', '/'));
            flyway(ds).migrate();
            // Dựng lại DB của bản cài cũ: V1 khi chưa có cột must_change_password, cùng checksum cũ đã lưu.
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            jdbc.update("""
                    INSERT INTO app_user (id, kind, last_name, first_name, search_text, role, username, password_hash,
                                          personal_code)
                    VALUES (1, 'PERSON', 'Quản trị', 'hệ thống', 'x', 'SUPER_ADMIN', 'admin', 'h', 'SA-ADMIN')
                    """);
            insertUser(jdbc, 2, "chua-dang-nhap", "h", null);
            insertUser(jdbc, 3, "da-dang-nhap", "h", "2026-11-01T00:00:00.000Z");
            insertUser(jdbc, 4, null, null, null);
            jdbc.execute("ALTER TABLE app_user DROP COLUMN must_change_password");
            setV1Checksum(db, oldChecksum);
            assertThatThrownBy(() -> flyway(ds).migrate()).hasMessageContaining("checksum mismatch");

            Optional<Path> backup = UpgradeBackup.migrate(flyway(ds), dataDir.toString(), CLOCK);
            assertThat(backup).isPresent();
            assertThat(backup.get().getFileName().toString()).endsWith("-v1.db");
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + backup.get().toString().replace('\\', '/'));
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM pragma_table_info('app_user') "
                         + "WHERE name = 'must_change_password'")) {
                assertThat(rs.getInt(1)).as("bản sao lưu là dữ liệu trước khi thêm cột").isZero();
            }
            assertThat(v1Checksum(db)).isEqualTo(UpgradeBackup.V1_CHECKSUM);
            // Chỉ tài khoản đã cấp mà chưa từng đăng nhập bị bắt đổi; Super Admin tạo lúc cài thì không.
            assertThat(jdbc.queryForList("SELECT id FROM app_user WHERE must_change_password = 1", Long.class))
                    .containsExactly(2L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(4);

            // Khởi động lại: không còn gì để làm, không sao lưu thêm.
            assertThat(UpgradeBackup.migrate(flyway(ds), dataDir.toString(), CLOCK)).isEmpty();
            assertThat(upgradeBackups(root)).hasSize(1);
        }
    }

    private static void insertUser(JdbcTemplate jdbc, long id, String username, String hash, String lastLogin) {
        jdbc.update("""
                INSERT INTO app_user (id, kind, last_name, first_name, search_text, unit_id, role, username,
                                      password_hash, personal_code, last_login_at, created_by)
                VALUES (?, 'PERSON', 'A', 'B', 'x', (SELECT id FROM unit WHERE code = '0002'), 'USER', ?, ?, ?, ?, 1)
                """, id, username, hash, "ND-" + id, lastLogin);
    }

    @Test
    void saoLuuTruocKhiNangCapVaGiuLaiKhiMigrationLoi() throws Exception {
        Path root = TestDirs.create("upgrade");
        Path dataDir = Files.createDirectories(root.resolve("data"));
        Path db = dataDir.resolve("trainingstats.db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db.toString().replace('\\', '/'));

        // Cài mới: DB trống, không có gì để sao lưu.
        assertThat(UpgradeBackup.migrate(flyway(ds), dataDir.toString(), CLOCK)).isEmpty();
        assertThat(version(db)).isEqualTo("1");
        // Khởi động lại, không có migration mới: không sao lưu.
        assertThat(UpgradeBackup.migrate(flyway(ds), dataDir.toString(), CLOCK)).isEmpty();
        assertThat(upgradeBackups(root)).isEmpty();

        // Bản mới có migration V900: sao lưu ở phiên bản 1 rồi nâng cấp.
        Optional<Path> b1 = UpgradeBackup.migrate(flyway(ds, "classpath:db/upgrade-ok"), dataDir.toString(), CLOCK);
        assertThat(b1).isPresent();
        assertThat(b1.get().getFileName().toString()).matches("trainingstats-upgrade-\\d{8}-\\d{6}(-\\d+)?-v1\\.db");
        assertThat(version(b1.get())).as("bản sao lưu là dữ liệu trước nâng cấp").isEqualTo("1");
        assertThat(version(db)).isEqualTo("900");

        // Migration lỗi: không khởi động được, dữ liệu vẫn ở 900, bản sao lưu trước nâng cấp còn nguyên.
        assertThatThrownBy(() -> UpgradeBackup.migrate(
                flyway(ds, "classpath:db/upgrade-ok", "classpath:db/upgrade-fail"), dataDir.toString(), CLOCK))
                .isInstanceOf(RuntimeException.class);
        assertThat(version(db)).isEqualTo("900");
        List<Path> backups = upgradeBackups(root);
        assertThat(backups).hasSize(2);
        assertThat(backups).anyMatch(p -> p.getFileName().toString().endsWith("-v900.db"));
        assertThat(backups).allMatch(p -> {
            try {
                return "1".equals(version(p)) || "900".equals(version(p));
            } catch (Exception e) {
                return false;
            }
        });
    }
}
