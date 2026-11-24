package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import vn.trainingstats.audit.AuditAction;

/** M3.3, M3.4 và phần tương ứng của M3.6: luồng đề nghị đầy đủ kèm thông báo, qua API thật. */
class RequestApiTest extends M3TestBase {

    private MockHttpSession sa;
    private MockHttpSession admin0002;     // người đề nghị
    private MockHttpSession admin0003;     // Admin nhánh khác
    private MockHttpSession teacher;       // người dùng thường, giáo viên của lớp
    private MockHttpSession plainUser;
    private long teacherId;
    private long classId;

    @BeforeEach
    void setUp() throws Exception {
        ensureUser("m3r-admin-0002", "ADMIN", "0002", PW, "ACTIVE");
        ensureUser("m3r-admin-0003", "ADMIN", "0003", PW, "ACTIVE");
        teacherId = ensureUser("m3r-gv", "USER", "0008", PW, "ACTIVE");
        ensureUser("m3r-user", "USER", "0002.0002", PW, "ACTIVE");
        sa = login(SA_USERNAME, SA_PASSWORD);
        admin0002 = login("m3r-admin-0002", PW);
        admin0003 = login("m3r-admin-0003", PW);
        teacher = login("m3r-gv", PW);
        plainUser = login("m3r-user", PW);
        classId = newClass(sa);
        setTeachers(sa, classId, teacherId);
    }

    private Res request(MockHttpSession s, String type, String targetKey, long targetId) throws Exception {
        return call(post("/api/v1/classes/" + classId + "/requests"), s, Map.of("type", type, targetKey, targetId));
    }

    @Test
    void luongDeNghiThemDuyetKemThongBao() throws Exception {
        long p = person(sa, "0002.0001", "ĐềNghị");
        int teacherUnread = unread(teacher);
        int saUnread = unread(sa);
        int requesterUnread = unread(admin0002);

        Res created = request(admin0002, "ADD", "userId", p);
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        long rid = created.id();
        assertThat(created.body().get("status").asText()).isEqualTo("PENDING");
        assertThat(created.body().get("access").get("cancel").asBoolean()).isTrue();
        assertThat(created.body().get("access").get("decide").asBoolean()).isFalse();
        assertThat(created.body().get("requestedUnitName").asText()).isNotBlank();

        // Báo cho giáo viên và Super Admin, không báo cho chính người đề nghị.
        assertThat(unread(teacher)).isEqualTo(teacherUnread + 1);
        assertThat(unread(sa)).isEqualTo(saUnread + 1);
        assertThat(unread(admin0002)).isEqualTo(requesterUnread);
        JsonNode note = ok(get("/api/v1/notifications").param("unread", "true"), teacher, null, 200).body().get("items").get(0);
        assertThat(note.get("refType").asText()).isEqualTo("class_request");
        assertThat(note.get("refId").asLong()).isEqualTo(rid);
        assertThat(note.get("body").asText()).contains("đề nghị thêm");

        // Không có hai đề nghị chờ giống hệt nhau.
        Res dup = request(admin0002, "ADD", "userId", p);
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.body().get("code").asText()).isEqualTo("DUPLICATE_REQUEST");

        // Giáo viên thấy và duyệt.
        JsonNode seen = ok(get("/api/v1/requests/" + rid), teacher, null, 200).body();
        assertThat(seen.get("access").get("decide").asBoolean()).isTrue();
        int auditBefore = countAudit(AuditAction.REQUEST_APPROVED, "m3r-gv");
        JsonNode approved = ok(post("/api/v1/requests/" + rid + "/approve"), teacher, Map.of(), 200).body();
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("decidedBy").get("id").asLong()).isEqualTo(teacherId);
        assertThat(countAudit(AuditAction.REQUEST_APPROVED, "m3r-gv")).isEqualTo(auditBefore + 1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT added_via, request_id, unit_id_snapshot FROM class_participant WHERE class_id = ? AND user_id = ?"
                        + " AND removed_at IS NULL", classId, p);
        assertThat(row.get("added_via")).isEqualTo("REQUEST");
        assertThat(((Number) row.get("request_id")).longValue()).isEqualTo(rid);
        assertThat(((Number) row.get("unit_id_snapshot")).longValue()).isEqualTo(unitId("0002.0001"));

        // Người đề nghị được báo.
        assertThat(unread(admin0002)).isEqualTo(requesterUnread + 1);
        assertThat(call(post("/api/v1/requests/" + rid + "/approve"), teacher, Map.of()).status()).isEqualTo(409);
        assertThat(call(post("/api/v1/requests/" + rid + "/cancel"), admin0002, null).status()).isEqualTo(409);
        // Đã có trong lớp thì không đề nghị thêm nữa.
        assertThat(request(admin0002, "ADD", "userId", p).body().get("code").asText()).isEqualTo("ALREADY_IN_CLASS");
    }

    @Test
    void tuChoiBatBuocLyDoVaBaoNguoiDeNghi() throws Exception {
        long p = person(sa, "0002.0002", "TừChối");
        long rid = request(admin0002, "ADD", "userId", p).id();
        int requesterUnread = unread(admin0002);
        assertThat(call(post("/api/v1/requests/" + rid + "/reject"), teacher, Map.of()).status()).isEqualTo(400);
        JsonNode rejected = ok(post("/api/v1/requests/" + rid + "/reject"), sa, Map.of("note", "Không đúng đối tượng"), 200)
                .body();
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get("decisionNote").asText()).isEqualTo("Không đúng đối tượng");
        assertThat(unread(admin0002)).isEqualTo(requesterUnread + 1);
        JsonNode note = ok(get("/api/v1/notifications"), admin0002, null, 200).body().get("items").get(0);
        assertThat(note.get("type").asText()).isEqualTo("REQUEST_REJECTED");
        assertThat(note.get("body").asText()).contains("Không đúng đối tượng");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM class_participant WHERE class_id = ? AND user_id = ?",
                Integer.class, classId, p)).isZero();
    }

    @Test
    void chiNguoiDeNghiTuHuyDuoc() throws Exception {
        long p = person(sa, "0002.0003", "Hủy");
        long rid = request(admin0002, "ADD", "userId", p).id();
        assertThat(call(post("/api/v1/requests/" + rid + "/cancel"), teacher, null).status()).isEqualTo(403);
        assertThat(call(post("/api/v1/requests/" + rid + "/cancel"), admin0003, null).status()).isEqualTo(404);
        JsonNode cancelled = ok(post("/api/v1/requests/" + rid + "/cancel"), admin0002, null, 200).body();
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(call(post("/api/v1/requests/" + rid + "/approve"), teacher, Map.of()).status()).isEqualTo(409);
        // Hủy xong thì đề nghị lại được (chỉ mục duy nhất chỉ áp cho đề nghị đang chờ).
        assertThat(request(admin0002, "ADD", "userId", p).status()).isEqualTo(201);
    }

    @Test
    void deNghiGoDuyetThiGoMem() throws Exception {
        long p = person(sa, "0002.0004", "Gỡ");
        long pid = addParticipant(sa, classId, p);
        assertThat(request(admin0002, "ADD", "userId", p).status()).as("đã có trong lớp").isEqualTo(409);
        long other = person(sa, "0002.0004", "KhôngTrongLớp");
        assertThat(request(admin0002, "REMOVE", "userId", other).body().get("code").asText()).isEqualTo("NOT_IN_CLASS");

        long rid = request(admin0002, "REMOVE", "userId", p).id();
        ok(post("/api/v1/requests/" + rid + "/approve"), teacher, Map.of("note", "Đồng ý"), 200);
        assertThat(jdbc.queryForObject("SELECT removed_at IS NOT NULL FROM class_participant WHERE id = ?", Integer.class,
                pid)).isEqualTo(1);
    }

    @Test
    void deNghiNgoaiNhanhHoacLopDongBiTuChoi() throws Exception {
        long outside = person(sa, "0003", "NgoàiNhánh");
        assertThat(request(admin0002, "ADD", "userId", outside).status()).isEqualTo(404);
        assertThat(request(admin0002, "ADD", "unitId", unitId("0003")).status()).isEqualTo(404);
        assertThat(call(post("/api/v1/classes/" + classId + "/requests"), admin0002, Map.of("type", "MOVE", "userId", outside))
                .status()).isEqualTo(400);

        // Đề nghị thêm đơn vị trong nhánh: tự tạo bản ghi đại diện.
        Res unitReq = request(admin0002, "ADD", "unitId", unitId("0002.0005"));
        assertThat(unitReq.status()).isEqualTo(201);
        assertThat(unitReq.body().get("target").get("kind").asText()).isEqualTo("UNIT");

        // Lớp đóng: không nhận đề nghị mới, không duyệt thêm người được.
        ok(patch("/api/v1/classes/" + classId + "/status"), sa, Map.of("status", "CLOSED"), 200);
        long inside = person(sa, "0002.0001", "LớpĐóng");
        // Lớp đóng, chưa có ai thuộc nhánh: Admin vẫn thấy lớp nhưng không đề nghị được.
        assertThat(request(admin0002, "ADD", "userId", inside).status()).isEqualTo(409);
        // Lớp đóng, có người thuộc nhánh: cũng vậy.
        ok(patch("/api/v1/classes/" + classId + "/status"), sa, Map.of("status", "OPEN"), 200);
        addParticipant(sa, classId, person(sa, "0002.0003", "TrongNhánh"));
        ok(patch("/api/v1/classes/" + classId + "/status"), sa, Map.of("status", "CLOSED"), 200);
        Res closed = request(admin0002, "ADD", "userId", inside);
        assertThat(closed.status()).isEqualTo(409);
        assertThat(closed.body().get("code").asText()).isEqualTo("CLASS_CLOSED");
        Res approveClosed = call(post("/api/v1/requests/" + unitReq.id() + "/approve"), teacher, Map.of());
        assertThat(approveClosed.status()).isEqualTo(409);
        assertThat(approveClosed.body().get("code").asText()).isEqualTo("CLASS_CLOSED");
    }

    @Test
    void chiAdminTaoDeNghi() throws Exception {
        long p = person(sa, "0008", "Quyền");
        assertThat(request(sa, "ADD", "userId", p).status()).as("Super Admin thêm trực tiếp").isEqualTo(403);
        assertThat(request(teacher, "ADD", "userId", p).status()).as("giáo viên thêm trực tiếp").isEqualTo(403);
        assertThat(request(plainUser, "ADD", "userId", p).status()).isEqualTo(403);
    }

    @Test
    void danhSachDeNghiTheoVaiTro() throws Exception {
        long rid = request(admin0002, "ADD", "userId", person(sa, "0002.0001", "XemDS")).id();

        assertThat(listIds(admin0002)).contains(rid);
        assertThat(listIds(teacher)).contains(rid);
        assertThat(listIds(sa)).contains(rid);
        assertThat(listIds(admin0003)).doesNotContain(rid);
        assertThat(listIds(plainUser)).isEmpty();
        assertThat(call(get("/api/v1/requests/" + rid), admin0003, null).status()).isEqualTo(404);
        assertThat(call(get("/api/v1/requests/" + rid), plainUser, null).status()).isEqualTo(404);
        assertThat(call(post("/api/v1/requests/" + rid + "/approve"), admin0003, Map.of()).status()).isEqualTo(404);
        // Người đề nghị không phải giáo viên thì không tự duyệt được.
        assertThat(call(post("/api/v1/requests/" + rid + "/approve"), admin0002, Map.of()).status()).isEqualTo(403);
        // Lọc theo trạng thái.
        JsonNode pending = ok(get("/api/v1/requests").param("status", "PENDING").param("size", "100"), teacher, null, 200)
                .body().get("items");
        pending.forEach(n -> assertThat(n.get("status").asText()).isEqualTo("PENDING"));
        assertThat(call(get("/api/v1/requests").param("status", "XYZ"), teacher, null).status()).isEqualTo(400);
        // Giáo viên chỉ nhận bốn trường của đối tượng, không có ngày sinh, điện thoại.
        assertThat(ok(get("/api/v1/requests/" + rid), teacher, null, 200).body().toString())
                .doesNotContain("1995-05-05", "0912345678");
    }

    @Test
    void docThongBaoChiCuaMinh() throws Exception {
        request(admin0002, "ADD", "userId", person(sa, "0002.0001", "ThôngBáo"));
        JsonNode items = ok(get("/api/v1/notifications").param("unread", "true"), teacher, null, 200).body().get("items");
        long nid = items.get(0).get("id").asLong();
        assertThat(call(post("/api/v1/notifications/" + nid + "/read"), admin0003, null).status()).isEqualTo(404);
        int before = unread(teacher);
        ok(post("/api/v1/notifications/" + nid + "/read"), teacher, null, 204);
        assertThat(unread(teacher)).isEqualTo(before - 1);
        ok(post("/api/v1/notifications/read-all"), teacher, null, 200);
        assertThat(unread(teacher)).isZero();
        assertThat(ok(get("/api/v1/notifications").param("unread", "true"), teacher, null, 200).body().get("total").asInt())
                .isZero();
        assertThat(ok(get("/api/v1/me"), teacher, null, 200).body().get("sessionTimeoutMinutes").asInt()).isPositive();
    }

    private List<Long> listIds(MockHttpSession s) throws Exception {
        return ids(ok(get("/api/v1/requests").param("size", "100"), s, null, 200).body().get("items"));
    }
}
