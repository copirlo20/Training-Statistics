package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.sqlite.SQLiteException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Kiểm chứng M0.2: các tính năng SQLite mà thiết kế (docs/ARCHITECTURE.md) phụ thuộc vào
 * có chạy được trên sqlite-jdbc và Flyway đang dùng hay không.
 */
// Schema mẫu riêng của M0 không có bảng app_user, nên tắt bước tạo Super Admin lần đầu.
@SpringBootTest(properties = {"spring.flyway.locations=classpath:db/spike", "app.first-run.enabled=false", "app.backup.auto=false"})
class SqliteSpikeTest {

    private static final Path DATA_DIR = createTempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", DATA_DIR::toString);
    }

    private static Path createTempDir() {
        return TestDirs.create("spike");
    }

    @Autowired
    @Qualifier("writeDataSource")
    DataSource writeDataSource;
    @Autowired
    @Qualifier("readDataSource")
    DataSource readDataSource;
    /** JdbcTemplate chính của ứng dụng: ngoài giao dịch ghi thì đi pool đọc. */
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txManager;

    /** Ghi thẳng qua pool ghi (autocommit), dùng cho các kiểm chứng mức SQLite không cần giao dịch của Spring. */
    private JdbcTemplate writeJdbc;

    @BeforeEach
    void setUpWriter() {
        writeJdbc = new JdbcTemplate(writeDataSource);
    }

    /** Spring không có bảng mã lỗi cho SQLite nên ném UncategorizedSQLException, ta đọc mã lỗi gốc từ SQLite. */
    private static String loiSqlite(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t instanceof SQLiteException s ? s.getResultCode().name() : t.getClass().getSimpleName();
    }

    private long newUnit(String code) {
        writeJdbc.update("INSERT INTO spike_unit(code) VALUES (?)", code);
        return jdbc.queryForObject("SELECT id FROM spike_unit WHERE code = ?", Long.class, code);
    }

    private long newParticipant(long classId, long userId, long unitId) {
        writeJdbc.update("INSERT INTO spike_participant(class_id, user_id, unit_id) VALUES (?,?,?)", classId, userId, unitId);
        return jdbc.queryForObject("SELECT MAX(id) FROM spike_participant", Long.class);
    }

    @Test
    void flywayChayDuocMigrationCoTriggerVaGeneratedColumn() {
        Integer ok = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version = '1'", Integer.class);
        assertThat(ok).isEqualTo(1);
    }

    @Test
    void khoaNgoaiBatOKetNoiGhiVaPoolDocChiDoc() throws Exception {
        try (Connection c = writeDataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA foreign_keys")) {
            rs.next();
            assertThat(rs.getInt(1)).as("foreign_keys ở kết nối ghi").isEqualTo(1);
        }
        assertThatThrownBy(() -> writeJdbc.update(
                "INSERT INTO spike_participant(class_id, user_id, unit_id) VALUES (1, 1, 999999)"))
                .satisfies(e -> assertThat(loiSqlite(e)).isEqualTo("SQLITE_CONSTRAINT_FOREIGNKEY"));
        // Pool đọc mở chế độ chỉ đọc: mọi lệnh ghi đều bị SQLite từ chối.
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                Connection c = readDataSource.getConnection();
                held.add(c);
                try (Statement s = c.createStatement()) {
                    assertThatThrownBy(() -> s.executeUpdate("INSERT INTO spike_audit(action) VALUES ('x')"))
                            .as("ghi qua kết nối đọc %d", i)
                            .satisfies(e -> assertThat(loiSqlite(e)).startsWith("SQLITE_READONLY"));
                }
            }
        } finally {
            for (Connection c : held) {
                c.close();
            }
        }
    }

    @Test
    void pragmaWalBusyTimeoutSynchronous() {
        assertThat(writeJdbc.queryForObject("PRAGMA journal_mode", String.class)).isEqualToIgnoringCase("wal");
        assertThat(writeJdbc.queryForObject("PRAGMA busy_timeout", Integer.class)).isEqualTo(5000);
        assertThat(writeJdbc.queryForObject("PRAGMA synchronous", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("PRAGMA busy_timeout", Integer.class)).as("pool đọc").isEqualTo(5000);
    }

    @Test
    void xepLoaiOCacDiemBien() {
        long unit = newUnit("grade-unit");
        int[][] cases = {{0, 0}, {49, 0}, {50, 1}, {65, 1}, {66, 2}, {79, 2}, {80, 3}, {100, 3}};
        String[] names = {"FAIL", "PASS", "GOOD", "EXCELLENT"};
        long userId = 100;
        for (int[] c : cases) {
            long p = newParticipant(900, userId++, unit);
            writeJdbc.update("INSERT INTO spike_result(participant_id, score_x10) VALUES (?, ?)", p, c[0]);
            String grade = jdbc.queryForObject(
                    "SELECT grade FROM spike_result WHERE participant_id = ?", String.class, p);
            assertThat(grade).as("điểm %.1f", c[0] / 10.0).isEqualTo(names[c[1]]);
        }
        long p = newParticipant(900, userId++, unit);
        assertThatThrownBy(() -> writeJdbc.update(
                "INSERT INTO spike_result(participant_id, score_x10) VALUES (?, 101)", p))
                .satisfies(e -> assertThat(loiSqlite(e)).isEqualTo("SQLITE_CONSTRAINT_CHECK"));
        assertThatThrownBy(() -> writeJdbc.update(
                "INSERT INTO spike_result(participant_id, score_x10) VALUES (?, -1)", p))
                .satisfies(e -> assertThat(loiSqlite(e)).isEqualTo("SQLITE_CONSTRAINT_CHECK"));
    }

    @Test
    void partialUniqueIndexChanTrungNhungChoThemLaiSauKhiGo() {
        long unit = newUnit("partial-unit");
        long first = newParticipant(901, 1, unit);
        assertThatThrownBy(() -> newParticipant(901, 1, unit))
                .satisfies(e -> assertThat(loiSqlite(e)).isEqualTo("SQLITE_CONSTRAINT_UNIQUE"));
        writeJdbc.update("UPDATE spike_participant SET removed_at = '2026-01-01T00:00:00Z' WHERE id = ?", first);
        long second = newParticipant(901, 1, unit);
        writeJdbc.update("UPDATE spike_participant SET removed_at = '2026-01-02T00:00:00Z' WHERE id = ?", second);
        long third = newParticipant(901, 1, unit);
        assertThat(third).isGreaterThan(second);
    }

    @Test
    void triggerChanXoaCungVaAuditChiDuocThem() {
        long unit = newUnit("trigger-unit");
        long p = newParticipant(902, 1, unit);
        assertThatThrownBy(() -> writeJdbc.update("DELETE FROM spike_participant WHERE id = ?", p))
                .hasMessageContaining("Khong duoc xoa cung");

        writeJdbc.update("INSERT INTO spike_audit(action) VALUES ('login')");
        assertThatThrownBy(() -> writeJdbc.update("UPDATE spike_audit SET action = 'x'"))
                .hasMessageContaining("audit chi duoc them");
        assertThatThrownBy(() -> writeJdbc.update("DELETE FROM spike_audit"))
                .hasMessageContaining("audit chi duoc them");
    }

    @Test
    void vacuumIntoTrenKetNoiChiDocKhiUngDungDangGhiVaMoLaiDuoc() throws Exception {
        for (int i = 0; i < 50; i++) {
            writeJdbc.update("INSERT INTO spike_audit(action) VALUES (?)", "before-" + i);
        }
        Path backup = DATA_DIR.resolve("backup-test.db");
        Files.deleteIfExists(backup);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicInteger written = new AtomicInteger();
        Future<?> writer = pool.submit(() -> {
            for (int i = 0; i < 200; i++) {
                writeJdbc.update("INSERT INTO spike_audit(action) VALUES (?)", "during-" + i);
                written.incrementAndGet();
            }
        });
        jdbc.execute("VACUUM INTO '" + backup.toString().replace('\\', '/') + "'");
        writer.get();
        pool.shutdown();

        assertThat(Files.size(backup)).isGreaterThan(0);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + backup.toString().replace('\\', '/'));
             Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("PRAGMA integrity_check")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("ok");
            }
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM spike_audit")) {
                rs.next();
                assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(50);
            }
            try (ResultSet rs = s.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    void nhieuLuongGhiCungLucKhongBiBusy() throws Exception {
        int threads = 8;
        int each = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            tasks.add(() -> {
                int ok = 0;
                for (int i = 0; i < each; i++) {
                    writeJdbc.update("INSERT INTO spike_audit(action) VALUES (?)", "t" + id + "-" + i);
                    ok++;
                }
                return ok;
            });
        }
        int total = 0;
        for (Future<Integer> f : pool.invokeAll(tasks)) {
            total += f.get();
        }
        pool.shutdown();
        assertThat(total).isEqualTo(threads * each);
    }

    @Test
    void giaoDichDocRoiGhiKhongBiBusyNhoTransactionModeImmediate() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            tasks.add(() -> {
                for (int i = 0; i < 25; i++) {
                    final int n = i;
                    tx.executeWithoutResult(status -> {
                        jdbc.queryForObject("SELECT COUNT(*) FROM spike_audit", Integer.class);
                        jdbc.update("INSERT INTO spike_audit(action) VALUES (?)", "rw" + id + "-" + n);
                    });
                }
                return true;
            });
        }
        for (Future<Boolean> f : pool.invokeAll(tasks)) {
            assertThat(f.get()).isTrue();
        }
        pool.shutdown();
    }

    @Test
    void haiGiamKhaoChamCungMotNguoiChiMotBenThanhCong() throws Exception {
        long unit = newUnit("race-unit");
        long p = newParticipant(903, 1, unit);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int score = 50 + t;
            tasks.add(() -> {
                try {
                    writeJdbc.update("INSERT INTO spike_result(participant_id, score_x10) VALUES (?, ?)", p, score);
                    success.incrementAndGet();
                } catch (RuntimeException e) {
                    if ("SQLITE_CONSTRAINT_UNIQUE".equals(loiSqlite(e))) {
                        rejected.incrementAndGet();
                    } else {
                        throw e;
                    }
                }
                return null;
            });
        }
        for (Future<Void> f : pool.invokeAll(tasks)) {
            f.get();
        }
        pool.shutdown();
        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
    }
}
