package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import vn.trainingstats.audit.AuditAction;

/**
 * M4.1, M4.2, M4.4: nhập điểm, sửa điểm, lịch sử, hồ sơ điểm cá nhân, qua API thật.
 * Cây dùng trong test: 0002 có con 0002.0001; 0003, 0004 là nhánh khác.
 */
class ResultApiTest extends M3TestBase {

    private MockHttpSession sa;
    private MockHttpSession admin0002;
    private MockHttpSession admin0003;
    private MockHttpSession teacher;     // người dùng thường ở 0004, được gán làm giáo viên
    private MockHttpSession otherTeacher;
    private MockHttpSession learner;     // người dùng thường ở 0002.0001, là người học
    private long teacherId;
    private long otherTeacherId;
    private long learnerId;
    private ExecutorService pool;

    @BeforeEach
    void setUp() throws Exception {
        ensureUser("m4-admin-0002", "ADMIN", "0002", PW, "ACTIVE");
        ensureUser("m4-admin-0003", "ADMIN", "0003", PW, "ACTIVE");
        teacherId = ensureUser("m4-gv", "USER", "0004", PW, "ACTIVE");
        otherTeacherId = ensureUser("m4-gv-khac", "USER", "0004", PW, "ACTIVE");
        learnerId = ensureUser("m4-hv", "USER", "0002.0001", PW, "ACTIVE");
        sa = login(SA_USERNAME, SA_PASSWORD);
        admin0002 = login("m4-admin-0002", PW);
        admin0003 = login("m4-admin-0003", PW);
        teacher = login("m4-gv", PW);
        otherTeacher = login("m4-gv-khac", PW);
        learner = login("m4-hv", PW);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private String resultUrl(long classId, long pid) {
        return "/api/v1/classes/" + classId + "/participants/" + pid + "/result";
    }

    private static Map<String, Object> score(String value) {
        Map<String, Object> m = new HashMap<>();
        m.put("score", value == null ? null : new BigDecimal(value));
        return m;
    }

    private Res grade(MockHttpSession s, long classId, long pid, String value) throws Exception {
        return call(post(resultUrl(classId, pid)), s, score(value));
    }

    /** Lớp có giáo viên {@code teacher} và một người học mới (không tài khoản) ở 0002.0001. */
    private long[] classWithLearner() throws Exception {
        long classId = newClass(sa);
        setTeachers(sa, classId, teacherId);
        long pid = addParticipant(sa, classId, person(sa, "0002.0001", "Học"));
        return new long[] {classId, pid};
    }

    private Map<String, Object> edit(String value, String reason, int version) {
        Map<String, Object> m = new HashMap<>();
        m.put("score", value == null ? null : new BigDecimal(value));
        m.put("reason", reason);
        m.put("version", version);
        return m;
    }

    // ------------------------------------------------------------------ M4.1 nhập điểm

    @Test
    void xepLoaiDungOCacDiemBien() throws Exception {
        long classId = newClass(sa);
        String[][] cases = {
                {"4.9", "49", "FAIL"}, {"5.0", "50", "PASS"}, {"6.5", "65", "PASS"}, {"6.6", "66", "GOOD"},
                {"7.9", "79", "GOOD"}, {"8.0", "80", "EXCELLENT"}, {"0", "0", "FAIL"}, {"10", "100", "EXCELLENT"},
                {"6.50", "65", "PASS"}};
        for (String[] c : cases) {
            long pid = addParticipant(sa, classId, person(sa, "0003", "Biên"));
            Res r = grade(sa, classId, pid, c[0]);
            assertThat(r.status()).as("điểm %s: %s", c[0], r.body()).isEqualTo(201);
            JsonNode result = r.body().get("result");
            assertThat(result.get("scoreX10").asInt()).as("điểm %s", c[0]).isEqualTo(Integer.parseInt(c[1]));
            assertThat(result.get("grade").asText()).as("xếp loại của %s", c[0]).isEqualTo(c[2]);
            assertThat(r.body().get("state").asText()).isEqualTo("TESTED");
        }
    }

    @Test
    void diemSaiDinhDangBiTuChoi() throws Exception {
        long classId = newClass(sa);
        long pid = addParticipant(sa, classId, person(sa, "0003", "Sai"));
        for (String bad : new String[] {"-0.1", "10.1", "6.55", "11", null}) {
            Res r = grade(sa, classId, pid, bad);
            assertThat(r.status()).as("điểm %s", bad).isEqualTo(400);
            assertThat(r.body().toString()).as("điểm %s", bad).contains("score");
        }
        assertThat(call(post(resultUrl(classId, pid)), sa, Map.of("score", "abc")).status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM result WHERE participant_id = ?", Integer.class, pid))
                .isZero();
    }

    @Test
    void chiSuperAdminVaGiaoVienCuaLopDuocNhap() throws Exception {
        long[] a = classWithLearner();
        long classId = a[0];
        long pid = a[1];
        long otherClass = newClass(sa);
        setTeachers(sa, otherClass, otherTeacherId);

        assertThat(grade(otherTeacher, classId, pid, "7").status()).as("giáo viên lớp khác").isEqualTo(404);
        assertThat(grade(admin0002, classId, pid, "7").status()).as("Admin có người học trong nhánh").isEqualTo(404);
        assertThat(grade(learner, classId, pid, "7").status()).as("người dùng thường").isEqualTo(404);
        assertThat(grade(otherTeacher, otherClass, pid, "7").status()).as("người học không thuộc lớp này")
                .isEqualTo(404);

        Res ok = grade(teacher, classId, pid, "7.5");
        assertThat(ok.status()).as(String.valueOf(ok.body())).isEqualTo(201);
        JsonNode result = ok.body().get("result");
        assertThat(result.get("gradedByName").asText()).as("lưu người nhập").isNotBlank();
        assertThat(result.get("gradedAt").asText()).as("lưu thời điểm nhập").isNotBlank();
        assertThat(result.get("edited").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT graded_by FROM result WHERE participant_id = ?", Long.class, pid))
                .isEqualTo(teacherId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_id = ?",
                Integer.class, AuditAction.RESULT_CREATED, result.get("id").asLong())).isEqualTo(1);
    }

    @Test
    void giaoVienKhongTuChamChoMinh() throws Exception {
        long classId = newClass(sa);
        setTeachers(sa, classId, teacherId);
        long selfPid = addParticipant(sa, classId, teacherId);
        Res r = grade(teacher, classId, selfPid, "9");
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body().get("code").asText()).isEqualTo("SELF_GRADING");
        assertThat(grade(sa, classId, selfPid, "9").status()).as("Super Admin chấm thay được").isEqualTo(201);
    }

    @Test
    void daCoDiemThi409VaNhapDiemXoaLyDoVang() throws Exception {
        long[] a = classWithLearner();
        long classId = a[0];
        long pid = a[1];
        long reasonId = jdbc.queryForObject("SELECT id FROM absence_reason WHERE active = 1 ORDER BY id LIMIT 1", Long.class);
        ok(put("/api/v1/classes/" + classId + "/participants/" + pid + "/absence"), teacher,
                Map.of("reasonId", reasonId, "note", "Ốm"), 200);

        Res first = grade(teacher, classId, pid, "8");
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.body().get("absence").isNull()).as("lý do vắng bị xóa cùng lúc").isTrue();
        assertThat(jdbc.queryForObject("SELECT absence_reason_id IS NULL AND absence_note IS NULL FROM class_participant WHERE id = ?",
                Integer.class, pid)).isEqualTo(1);

        Res again = grade(sa, classId, pid, "9");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().get("code").asText()).isEqualTo("ALREADY_GRADED");

        // Lớp đóng vẫn nhập được điểm (đóng lớp chỉ chặn thêm người, C9); người đã gỡ thì không.
        long other = addParticipant(sa, classId, person(sa, "0002.0001", "Đóng"));
        long removed = addParticipant(sa, classId, person(sa, "0002.0001", "Gỡ"));
        ok(post("/api/v1/classes/" + classId + "/participants/" + removed + "/remove"), sa, Map.of(), 200);
        ok(patch("/api/v1/classes/" + classId + "/status"), sa, Map.of("status", "CLOSED"), 200);
        assertThat(grade(teacher, classId, other, "6").status()).isEqualTo(201);
        Res r = grade(teacher, classId, removed, "6");
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body().get("code").asText()).isEqualTo("ALREADY_REMOVED");
    }

    @Test
    void nhapTrungDongThoiChiMotBenThanhCong() throws Exception {
        long classId = newClass(sa);
        setTeachers(sa, classId, teacherId);
        pool = Executors.newFixedThreadPool(2);
        for (int round = 0; round < 5; round++) {
            long pid = addParticipant(sa, classId, person(sa, "0003", "Đồng thời"));
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> calls = new ArrayList<>();
            for (MockHttpSession s : List.of(sa, teacher)) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return grade(s, classId, pid, s == sa ? "9" : "4").status();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : calls) {
                statuses.add(f.get());
            }
            assertThat(statuses).as("vòng %d", round).containsExactlyInAnyOrder(201, 409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM result WHERE participant_id = ?", Integer.class, pid))
                    .isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ M4.2 sửa điểm

    @Test
    void suaDiemChiSuperAdminCoLyDoVaLichSu() throws Exception {
        long[] a = classWithLearner();
        long classId = a[0];
        long pid = a[1];
        JsonNode result = grade(teacher, classId, pid, "6.5").body().get("result");
        long rid = result.get("id").asLong();
        int version = result.get("version").asInt();
        String url = "/api/v1/results/" + rid;

        assertThat(call(put(url), teacher, edit("7", "Chấm nhầm", version)).status()).as("giáo viên").isEqualTo(403);
        assertThat(call(put(url), admin0002, edit("7", "Chấm nhầm", version)).status()).as("Admin").isEqualTo(403);
        assertThat(call(get(url + "/history"), teacher, null).status()).isEqualTo(403);
        assertThat(call(get(url + "/history"), admin0002, null).status()).isEqualTo(403);

        Res noReason = call(put(url), sa, edit("7", "  ", version));
        assertThat(noReason.status()).isEqualTo(400);
        assertThat(noReason.body().toString()).contains("reason");
        Res same = call(put(url), sa, edit("6.5", "Không đổi", version));
        assertThat(same.status()).isEqualTo(400);
        assertThat(same.body().toString()).contains("score");
        assertThat(call(put(url), sa, edit("7", "Sai version", version + 5)).status()).isEqualTo(409);
        Map<String, Object> noVersion = edit("7", "Thiếu version", 0);
        noVersion.remove("version");
        assertThat(call(put(url), sa, noVersion).status()).isEqualTo(400);

        Res edited = ok(put(url), sa, edit("6.6", "Cộng sót 0.1 điểm", version), 200);
        JsonNode after = edited.body().get("result");
        assertThat(after.get("scoreX10").asInt()).isEqualTo(66);
        assertThat(after.get("grade").asText()).as("xếp loại tính lại ở DB").isEqualTo("GOOD");
        assertThat(after.get("edited").asBoolean()).isTrue();
        assertThat(after.get("version").asInt()).isEqualTo(version + 1);
        ok(put(url), sa, edit("8", "Phúc khảo", version + 1), 200);

        JsonNode history = ok(get(url + "/history"), sa, null, 200).body();
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("oldScoreX10").asInt()).as("mới nhất trước").isEqualTo(66);
        assertThat(history.get(0).get("newScoreX10").asInt()).isEqualTo(80);
        assertThat(history.get(0).get("reason").asText()).isEqualTo("Phúc khảo");
        assertThat(history.get(1).get("oldScoreX10").asInt()).isEqualTo(65);
        assertThat(history.get(1).get("changedByName").asText()).isNotBlank();

        List<Map<String, Object>> audits = jdbc.queryForList(
                "SELECT before_json, after_json FROM audit_log WHERE action = ? AND entity_id = ? ORDER BY id",
                AuditAction.RESULT_UPDATED, rid);
        assertThat(audits).hasSize(2);
        assertThat(audits.get(0).get("before_json").toString()).contains("65");
        assertThat(audits.get(0).get("after_json").toString()).contains("66", "Cộng sót 0.1 điểm");

        assertThat(call(put("/api/v1/results/999999"), sa, edit("5", "Không có", 0)).status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ M4.4 hồ sơ

    @Test
    void hoSoChiCuaMinhVaKhongThayDiemDonVi() throws Exception {
        long tested = newClass(sa);
        long absent = newClass(sa);
        long removedClass = newClass(sa);
        long pTested = addParticipant(sa, tested, learnerId);
        long pAbsent = addParticipant(sa, absent, learnerId);
        long pRemoved = addParticipant(sa, removedClass, learnerId);
        grade(sa, tested, pTested, "7.9");
        long reasonId = jdbc.queryForObject("SELECT id FROM absence_reason WHERE active = 1 ORDER BY id LIMIT 1", Long.class);
        ok(put("/api/v1/classes/" + absent + "/participants/" + pAbsent + "/absence"), sa, Map.of("reasonId", reasonId), 200);
        grade(sa, removedClass, pRemoved, "3");
        ok(post("/api/v1/classes/" + removedClass + "/participants/" + pRemoved + "/remove"), sa,
                Map.of("reason", "Nhầm người"), 200);

        // Đơn vị của người học (0002.0001) cũng được chấm điểm trong lớp "tested".
        Res unitRow = ok(post("/api/v1/classes/" + tested + "/participants"), sa,
                Map.of("unitId", unitId("0002.0001")), 201);
        long unitRecordId = unitRow.body().get("user").get("id").asLong();
        grade(sa, tested, unitRow.id(), "9");

        JsonNode mine = ok(get("/api/v1/me/classes"), learner, null, 200).body();
        Map<Long, JsonNode> byClass = new HashMap<>();
        mine.forEach(n -> byClass.put(n.get("classInfo").get("id").asLong(), n));
        assertThat(byClass.keySet()).as("không có lớp đã bị gỡ").containsExactlyInAnyOrder(tested, absent);
        assertThat(byClass.get(tested).get("state").asText()).isEqualTo("TESTED");
        assertThat(byClass.get(tested).get("result").get("scoreX10").asInt()).isEqualTo(79);
        assertThat(byClass.get(tested).get("result").get("grade").asText()).isEqualTo("GOOD");
        assertThat(byClass.get(absent).get("state").asText()).isEqualTo("ABSENT");
        assertThat(byClass.get(absent).get("absence").get("reasonName").asText()).isNotBlank();
        assertThat(mine.toString()).as("không lộ điểm đơn vị (9.0)").doesNotContain("\"scoreX10\":90");

        // Danh sách người học của lớp: người học chỉ thấy chính mình, không thấy dòng đơn vị (Q5).
        JsonNode list = ok(get("/api/v1/classes/" + tested + "/participants"), learner, null, 200).body();
        assertThat(userIds(list)).containsExactly(learnerId);

        String own = "/api/v1/users/" + learnerId + "/history";
        String unit = "/api/v1/users/" + unitRecordId + "/history";
        assertThat(ok(get(own), learner, null, 200).body()).hasSize(2);
        assertThat(call(get(unit), learner, null).status()).as("thành viên không xem điểm đơn vị").isEqualTo(404);
        assertThat(call(get("/api/v1/users/" + teacherId + "/history"), learner, null).status()).isEqualTo(404);

        assertThat(ok(get(own), admin0002, null, 200).body()).as("Admin trong nhánh").hasSize(2);
        assertThat(call(get(own), admin0003, null).status()).as("Admin nhánh khác").isEqualTo(404);
        JsonNode unitHistory = ok(get(unit), admin0002, null, 200).body();
        assertThat(unitHistory.get(0).get("result").get("scoreX10").asInt()).as("Admin trong nhánh xem điểm đơn vị")
                .isEqualTo(90);
        assertThat(ok(get(own), sa, null, 200).body()).hasSize(2);
        assertThat(call(get(own), teacher, null).status()).as("giáo viên không xem hồ sơ").isEqualTo(404);
    }
}
