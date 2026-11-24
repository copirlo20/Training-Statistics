package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import vn.trainingstats.admin.BackupFiles;
import vn.trainingstats.admin.BackupService;

/** M7.1, M7.3: sao lưu thủ công và tự động, xoay vòng giữ N bản, tải về, cài đặt, tình trạng hệ thống. */
class BackupApiTest extends M3TestBase {

    @Autowired
    private BackupService backups;

    private MockHttpSession sa;
    private MockHttpSession admin;
    private MockHttpSession plain;

    @BeforeEach
    void setUp() throws Exception {
        ensureUser("m7-admin", "ADMIN", "0002", PW, "ACTIVE");
        ensureUser("m7-user", "USER", "0002.0001", PW, "ACTIVE");
        sa = login(SA_USERNAME, SA_PASSWORD);
        admin = login("m7-admin", PW);
        plain = login("m7-user", PW);
    }

    @Test
    void saoLuuThuCongMoLaiDuocVaTaiVe() throws Exception {
        String tag = uniq("sao-luu");
        ensureUser(tag, "USER", "0003", PW, "ACTIVE");

        JsonNode e = ok(post("/api/v1/admin/backup"), sa, null, 201).body();
        String name = e.get("name").asText();
        assertThat(name).matches("trainingstats-manual-\\d{8}-\\d{6}(-\\d+)?\\.db");
        Path file = backups.dir().resolve(name);
        assertThat(file).exists();
        assertThat(Files.list(backups.dir()).map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".part"));

        // Bản sao lưu là DB nguyên vẹn, có cả dữ liệu vừa ghi.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toString().replace('\\', '/'));
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                assertThat(rs.next() && "ok".equals(rs.getString(1))).isTrue();
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM app_user WHERE username = '" + tag + "'")) {
                assertThat(rs.next() && rs.getInt(1) == 1).as("bản sao có dữ liệu mới").isTrue();
            }
        }

        JsonNode list = ok(get("/api/v1/admin/backups"), sa, null, 200).body();
        assertThat(list.get("items").findValuesAsText("name")).contains(name);
        assertThat(list.get("settings").get("keep").asInt()).isBetween(1, 30);

        MockHttpServletResponse dl = mvc.perform(get("/api/v1/admin/backups/" + name).session(sa)).andReturn().getResponse();
        assertThat(dl.getStatus()).isEqualTo(200);
        assertThat(dl.getHeader("Content-Disposition")).contains("attachment").contains(name);
        assertThat(dl.getContentAsByteArray().length).isEqualTo((int) Files.size(file));

        // Tên lạ (đường dẫn vượt thư mục) hoặc không có: 404.
        assertThat(call(get("/api/v1/admin/backups/..%2Ftrainingstats.db"), sa, null).status()).isIn(400, 404);
        assertThat(call(get("/api/v1/admin/backups/trainingstats-manual-20000101-000000.db"), sa, null).status())
                .isEqualTo(404);

        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action IN ('BACKUP_CREATED', 'BACKUP_DOWNLOADED') AND after_json LIKE ?",
                Integer.class, "%" + name + "%");
        assertThat(audits).as("tạo và tải bản sao lưu đều ghi nhật ký").isEqualTo(2);
    }

    @Test
    void chiSuperAdminDungDuoc() throws Exception {
        for (MockHttpSession s : List.of(admin, plain)) {
            assertThat(call(get("/api/v1/admin/backups"), s, null).status()).isEqualTo(403);
            assertThat(call(post("/api/v1/admin/backup"), s, null).status()).isEqualTo(403);
            assertThat(call(get("/api/v1/admin/health"), s, null).status()).isEqualTo(403);
            assertThat(call(put("/api/v1/admin/backup-settings"), s, Map.of("time", "03:00", "keep", 5, "dir", ""))
                    .status()).isEqualTo(403);
        }
    }

    @Test
    void tuDongXoayVongChiGiuSoBanCauHinh() throws Exception {
        Path dir = backups.dir();
        // Các bản tự động "cũ": tên đúng mẫu, thời điểm sửa lùi dần.
        for (int i = 1; i <= 7; i++) {
            Path p = Files.createDirectories(dir).resolve("trainingstats-auto-2000010" + i + "-020000.db");
            Files.writeString(p, "x");
            Files.setLastModifiedTime(p, FileTime.from(Instant.parse("2000-01-0" + i + "T00:00:00Z")));
        }
        Path manual = dir.resolve("trainingstats-manual-19990101-000000.db");
        Files.writeString(manual, "x");
        Files.setLastModifiedTime(manual, FileTime.from(Instant.parse("1999-01-01T00:00:00Z")));

        ok(put("/api/v1/admin/backup-settings"), sa, Map.of("time", "02:00", "keep", 5, "dir", ""), 200);
        assertThat(backups.runAuto()).isPresent();
        List<String> autos = Files.list(dir).map(p -> p.getFileName().toString())
                .filter(n -> n.startsWith("trainingstats-auto-")).toList();
        assertThat(autos).hasSize(5);
        assertThat(autos).noneMatch(n -> n.startsWith("trainingstats-auto-2000010" + "1"))
                .noneMatch(n -> n.startsWith("trainingstats-auto-2000010" + "2"))
                .noneMatch(n -> n.startsWith("trainingstats-auto-2000010" + "3"));
        assertThat(manual).as("bản thủ công không bị xoay vòng").exists();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'BACKUP_AUTO_CREATED'", Integer.class))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void denLuotSaoLuuTheoGioVietNamVaMoiNgayMotLan() {
        ZoneId vn = ZoneId.of("Asia/Ho_Chi_Minh");
        Instant before = ZonedDateTime.of(2026, 11, 22, 1, 59, 0, 0, vn).toInstant();
        Instant after = ZonedDateTime.of(2026, 11, 22, 2, 0, 0, 0, vn).toInstant();
        Instant laterSameDay = ZonedDateTime.of(2026, 11, 22, 23, 0, 0, 0, vn).toInstant();
        Instant yesterday = ZonedDateTime.of(2026, 11, 21, 2, 0, 5, 0, vn).toInstant();
        LocalTime at = LocalTime.of(2, 0);
        assertThat(BackupService.due(before, at, Optional.empty())).isFalse();
        assertThat(BackupService.due(after, at, Optional.empty())).isTrue();
        assertThat(BackupService.due(after, at, Optional.of(yesterday))).isTrue();
        assertThat(BackupService.due(laterSameDay, at, Optional.of(after))).as("đã có bản hôm nay").isFalse();
        // Máy tắt lúc 02:00: bật lại trong ngày thì sao lưu bù.
        assertThat(BackupService.due(laterSameDay, at, Optional.of(yesterday))).isTrue();
    }

    @Test
    void caiDatSaoLuuKiemDuLieuVaThuMucKhac() throws Exception {
        Res bad = call(put("/api/v1/admin/backup-settings"), sa, Map.of("time", "25:00", "keep", 0, "dir", "tuong-doi"));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("fieldErrors").findValuesAsText("field")).contains("time", "keep", "dir");

        Path other = Files.createTempDirectory("ts-backup-other");
        Map<String, Object> body = new HashMap<>();
        body.put("time", "3:30");
        body.put("keep", 7);
        body.put("dir", other.toString());
        JsonNode s = ok(put("/api/v1/admin/backup-settings"), sa, body, 200).body();
        assertThat(s.get("time").asText()).isEqualTo("03:30");
        assertThat(s.get("effectiveDir").asText()).isEqualTo(other.toAbsolutePath().normalize().toString());
        String name = ok(post("/api/v1/admin/backup"), sa, null, 201).body().get("name").asText();
        assertThat(other.resolve(name)).exists();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'SETTINGS_UPDATED'", Integer.class))
                .isGreaterThanOrEqualTo(1);
        // Trả về mặc định cho các test khác.
        ok(put("/api/v1/admin/backup-settings"), sa, Map.of("time", "02:00", "keep", 5, "dir", ""), 200);
    }

    @Test
    void tinhTrangHeThong() throws Exception {
        JsonNode h = ok(get("/api/v1/admin/health"), sa, null, 200).body();
        assertThat(h.get("schemaVersion").asText()).isNotBlank();
        assertThat(h.get("dbSizeBytes").asLong()).isPositive();
        assertThat(h.get("appVersion").asText()).isNotBlank();
        assertThat(h.get("backupDir").asText()).isEqualTo(backups.dir().toString());
        assertThat(h.get("warnings").isArray()).isTrue();
        assertThat(BackupFiles.UPGRADE_DIR).isEqualTo("upgrade");
    }
}
