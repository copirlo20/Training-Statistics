package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import vn.trainingstats.audit.AuditAction;
import vn.trainingstats.common.VietnameseText;

/**
 * M3.1, M3.2 và phần tương ứng của M3.6: đợt, lớp, giáo viên, người học, ứng viên, qua API thật.
 * Cây dùng trong test: 0002 có con 0002.0001..; 0003, 0004 là nhánh khác.
 */
class ClassApiTest extends M3TestBase {

    private MockHttpSession sa;
    private MockHttpSession admin0002;
    private MockHttpSession admin0003;
    private MockHttpSession admin0004;
    private MockHttpSession teacher;     // người dùng thường, được gán làm giáo viên
    private MockHttpSession plainUser;   // người dùng thường, không liên quan
    private long teacherId;
    private long plainUserId;

    @BeforeEach
    void setUp() throws Exception {
        ensureUser("m3c-admin-0002", "ADMIN", "0002", PW, "ACTIVE");
        ensureUser("m3c-admin-0003", "ADMIN", "0003", PW, "ACTIVE");
        ensureUser("m3c-admin-0004", "ADMIN", "0004", PW, "ACTIVE");
        teacherId = ensureUser("m3c-gv", "USER", "0004", PW, "ACTIVE");
        plainUserId = ensureUser("m3c-user", "USER", "0002.0001", PW, "ACTIVE");
        sa = login(SA_USERNAME, SA_PASSWORD);
        admin0002 = login("m3c-admin-0002", PW);
        admin0003 = login("m3c-admin-0003", PW);
        admin0004 = login("m3c-admin-0004", PW);
        teacher = login("m3c-gv", PW);
        plainUser = login("m3c-user", PW);
    }

    @Test
    void themSuaNoiDungKemGiamKhaoTrongMotGiaoDich() throws Exception {
        long period = period(sa);
        Map<String, Object> body = new HashMap<>();
        body.put("periodId", period);
        body.put("name", uniq("Nội dung kèm giám khảo"));
        body.put("teacherIds", List.of(teacherId));
        JsonNode created = ok(post("/api/v1/classes"), sa, body, 201).body();
        long id = created.get("id").asLong();
        assertThat(ids(created.get("teachers"))).containsExactly(teacherId);

        // Giám khảo không hợp lệ: lỗi gắn vào teacherIds, cả thao tác lưu bị hoàn tác (tên không đổi).
        Map<String, Object> upd = new HashMap<>();
        upd.put("periodId", period);
        upd.put("name", "Tên mới không được lưu");
        upd.put("version", created.get("version").asInt());
        upd.put("teacherIds", List.of(person(sa, "0002.0001", "KhongTaiKhoan")));
        Res bad = call(put("/api/v1/classes/" + id), sa, upd);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("fieldErrors").get(0).get("field").asText()).isEqualTo("teacherIds");
        assertThat(jdbc.queryForObject("SELECT name FROM exam_class WHERE id = ?", String.class, id))
                .isEqualTo(created.get("name").asText());

        // Không gửi teacherIds: giữ nguyên; gửi rỗng: bỏ hết.
        upd.remove("teacherIds");
        upd.put("name", "Tên đã đổi");
        JsonNode kept = ok(put("/api/v1/classes/" + id), sa, upd, 200).body();
        assertThat(ids(kept.get("teachers"))).containsExactly(teacherId);
        upd.put("version", kept.get("version").asInt());
        upd.put("teacherIds", List.of());
        assertThat(ok(put("/api/v1/classes/" + id), sa, upd, 200).body().get("teachers").size()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'CLASS_TEACHERS_CHANGED' AND entity_id = ?",
                Integer.class, id)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ M3.1 đợt

    @Test
    void dotKiemTraThemSuaDongXoaMem() throws Exception {
        Res bad = call(post("/api/v1/periods"), sa, Map.of("name", "Đợt sai", "startDate", "2026-12-01",
                "endDate", "2026-11-01"));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().toString()).contains("endDate");

        long id = period(sa);
        Res got = ok(get("/api/v1/periods/" + id), plainUser, null, 200);
        int version = got.body().get("version").asInt();
        ok(put("/api/v1/periods/" + id), sa, Map.of("name", "Đợt đã sửa", "version", version), 200);
        assertThat(call(put("/api/v1/periods/" + id), sa, Map.of("name", "Sửa chồng", "version", version)).status())
                .as("sai version").isEqualTo(409);

        ok(patch("/api/v1/periods/" + id + "/status"), sa, Map.of("status", "CLOSED"), 200);
        Res classInClosed = call(post("/api/v1/classes"), sa, Map.of("periodId", id,
                "name", "Nội dung trong đợt đóng"));
        assertThat(classInClosed.status()).isEqualTo(400);
        assertThat(classInClosed.body().toString()).contains("periodId");

        ok(post("/api/v1/periods/" + id + "/delete"), sa, null, 204);
        assertThat(call(get("/api/v1/periods/" + id), sa, null).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_period WHERE id = ?", Integer.class, id))
                .as("xóa mềm, bản ghi còn").isEqualTo(1);

        // Đợt còn lớp thì không xóa được.
        long classId = newClass(sa);
        long periodOfClass = jdbc.queryForObject("SELECT period_id FROM exam_class WHERE id = ?", Long.class, classId);
        assertThat(call(post("/api/v1/periods/" + periodOfClass + "/delete"), sa, null).status()).isEqualTo(409);
    }

    // ------------------------------------------------------------------ M3.1 lớp

    @Test
    void lopThemSuaDongMoXoaMem() throws Exception {
        long classId = newClass(sa);
        JsonNode c = ok(get("/api/v1/classes/" + classId), sa, null, 200).body();
        assertThat(c.get("status").asText()).isEqualTo("OPEN");
        assertThat(c.get("access").get("edit").asBoolean()).isTrue();

        Map<String, Object> body = new HashMap<>();
        body.put("periodId", c.get("period").get("id").asLong());
        body.put("name", "Lớp đã đổi tên");
        body.put("version", c.get("version").asInt());
        ok(put("/api/v1/classes/" + classId), sa, body, 200);
        assertThat(call(put("/api/v1/classes/" + classId), sa, body).status()).as("sai version").isEqualTo(409);

        Map<String, Object> noName = new HashMap<>(body);
        noName.remove("name");
        noName.put("version", c.get("version").asInt() + 1);
        assertThat(call(put("/api/v1/classes/" + classId), sa, noName).body().toString()).contains("name");
        assertThat(c.has("subject")).as("không còn trường môn kiểm tra").isFalse();

        // Lớp đóng không nhận thêm người.
        ok(patch("/api/v1/classes/" + classId + "/status"), sa, Map.of("status", "CLOSED"), 200);
        long p = person(sa, "0002.0001", "Đóng");
        Res closed = call(post("/api/v1/classes/" + classId + "/participants"), sa, Map.of("userId", p));
        assertThat(closed.status()).isEqualTo(409);
        assertThat(closed.body().get("code").asText()).isEqualTo("CLASS_CLOSED");
        ok(patch("/api/v1/classes/" + classId + "/status"), sa, Map.of("status", "OPEN"), 200);
        addParticipant(sa, classId, p);

        ok(post("/api/v1/classes/" + classId + "/delete"), sa, null, 204);
        assertThat(call(get("/api/v1/classes/" + classId), sa, null).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM exam_class WHERE id = ?", Integer.class, classId))
                .isEqualTo(1);
    }

    @Test
    void danhSachLopDangMoChoAdminChon() throws Exception {
        long open = newClass(sa);
        long closed = newClass(sa);
        ok(patch("/api/v1/classes/" + closed + "/status"), sa, Map.of("status", "CLOSED"), 200);
        JsonNode list = ok(get("/api/v1/classes/open"), admin0004, null, 200).body();
        assertThat(ids(list)).contains(open).doesNotContain(closed);
        List<String> fields = new ArrayList<>();
        list.get(0).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "name", "periodName", "examDate");
        assertThat(call(get("/api/v1/classes/open"), teacher, null).status()).isEqualTo(403);
    }

    @Test
    void lopDaCoDiemKhongXoaDuoc() throws Exception {
        long classId = newClass(sa);
        long pid = addParticipant(sa, classId, person(sa, "0002.0001", "Điểm"));
        insertResult(pid, 70);
        Res r = call(post("/api/v1/classes/" + classId + "/delete"), sa, null);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body().get("code").asText()).isEqualTo("CLASS_HAS_RESULTS");
    }

    @Test
    void ganGiaoVienChiNguoiCoTaiKhoanDangHoatDong() throws Exception {
        long classId = newClass(sa);
        long noAccount = person(sa, "0003", "Không tài khoản");
        long locked = ensureUser("m3c-khoa", "USER", "0003", PW, "INACTIVE");
        for (long bad : new long[] {noAccount, locked}) {
            Res r = call(put("/api/v1/classes/" + classId + "/teachers"), sa, Map.of("userIds", List.of(bad)));
            assertThat(r.status()).isEqualTo(400);
            assertThat(r.body().toString()).contains("userIds");
        }
        // Bản ghi đại diện đơn vị (UNIT) không làm giáo viên được.
        ok(post("/api/v1/classes/" + classId + "/participants"), sa, Map.of("unitId", unitId("0005")), 201);
        long unitRecord = jdbc.queryForObject("SELECT id FROM app_user WHERE kind = 'UNIT' AND unit_id = ?", Long.class,
                unitId("0005"));
        assertThat(call(put("/api/v1/classes/" + classId + "/teachers"), sa, Map.of("userIds", List.of(unitRecord)))
                .status()).isEqualTo(400);

        setTeachers(sa, classId, teacherId);
        JsonNode me = ok(get("/api/v1/me"), teacher, null, 200).body();
        assertThat(ids(me.get("teachingClasses"))).contains(classId);
        JsonNode detail = ok(get("/api/v1/classes/" + classId), teacher, null, 200).body();
        assertThat(detail.get("access").get("manage").asBoolean()).isTrue();
        assertThat(detail.get("access").get("edit").asBoolean()).isFalse();
        assertThat(ids(detail.get("teachers"))).containsExactly(teacherId);
        // Bỏ gán thì mất quyền ngay.
        setTeachers(sa, classId);
        assertThat(call(get("/api/v1/classes/" + classId), teacher, null).status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ M3.1, M3.2 quyền xem theo vai trò

    @Test
    void adminChiThayNguoiHocTrongNhanhMinhOLopNhieuDonVi() throws Exception {
        long classId = newClass(sa);
        setTeachers(sa, classId, teacherId);
        long a1 = person(sa, "0002.0001", "NhánhA1");
        long a2 = person(sa, "0002", "NhánhA2");
        long b1 = person(sa, "0003", "NhánhB1");
        for (long u : new long[] {a1, a2, b1, plainUserId}) {
            addParticipant(teacher, classId, u);
        }

        assertThat(userIds(ok(get("/api/v1/classes/" + classId + "/participants"), admin0002, null, 200).body()))
                .containsExactlyInAnyOrder(a1, a2, plainUserId);
        assertThat(userIds(ok(get("/api/v1/classes/" + classId + "/participants"), admin0003, null, 200).body()))
                .containsExactly(b1);
        assertThat(userIds(ok(get("/api/v1/classes/" + classId + "/participants"), teacher, null, 200).body()))
                .containsExactlyInAnyOrder(a1, a2, b1, plainUserId);
        assertThat(userIds(ok(get("/api/v1/classes/" + classId + "/participants"), sa, null, 200).body()))
                .containsExactlyInAnyOrder(a1, a2, b1, plainUserId);
        // Người học chỉ thấy chính mình.
        assertThat(userIds(ok(get("/api/v1/classes/" + classId + "/participants"), plainUser, null, 200).body()))
                .containsExactly(plainUserId);

        // Danh sách lớp: Admin 0002 và 0003 thấy lớp, số người học chỉ tính người trong nhánh.
        assertThat(countInList(admin0002, classId)).isEqualTo(3);
        assertThat(countInList(admin0003, classId)).isEqualTo(1);
        assertThat(countInList(teacher, classId)).isEqualTo(4);
        assertThat(countInList(plainUser, classId)).isEqualTo(1);
        // Admin 0004 không có người trong lớp: vẫn thấy lớp trong danh sách (đếm 0) và xem được thông tin chung, kể cả
        // khi lớp đã đóng; danh sách người học và thống kê chỉ gồm người trong nhánh nên rỗng.
        assertThat(countInList(admin0004, classId)).isEqualTo(0);
        assertThat(ok(get("/api/v1/classes/" + classId + "/participants"), admin0004, null, 200).body()).isEmpty();
        ok(patch("/api/v1/classes/" + classId + "/status"), sa, Map.of("status", "CLOSED"), 200);
        JsonNode closed = ok(get("/api/v1/classes/" + classId), admin0004, null, 200).body();
        assertThat(closed.get("participantCount").asInt()).isZero();
        assertThat(closed.get("access").get("manage").asBoolean()).isFalse();
        assertThat(countInList(admin0004, classId)).isEqualTo(0);
        assertThat(ok(get("/api/v1/stats/class/" + classId), admin0004, null, 200).body().get("counts").get("total").asInt()).isZero();
        assertThat(ok(get("/api/v1/stats/class/" + classId), admin0002, null, 200).body().get("counts").get("total").asInt()).isEqualTo(3);

        // Người dùng thường không liên quan: 404.
        MockHttpSession other = login(SA_USERNAME, SA_PASSWORD);
        long otherClass = newClass(other);
        assertThat(call(get("/api/v1/classes/" + otherClass), plainUser, null).status()).isEqualTo(404);
        assertThat(call(get("/api/v1/classes/" + otherClass + "/participants"), plainUser, null).status()).isEqualTo(404);
        assertThat(countInList(plainUser, otherClass)).isEqualTo(-1);
    }

    @Test
    void chuyenDonViThiAdminCuMatQuyenXemChiTietNguoiHoc() throws Exception {
        long classId = newClass(sa);
        long a1 = person(sa, "0002.0001", "SẽChuyển");
        addParticipant(sa, classId, a1);
        assertThat(userIds(ok(get("/api/v1/classes/" + classId + "/participants"), admin0002, null, 200).body()))
                .contains(a1);
        ok(post("/api/v1/users/" + a1 + "/move"), sa, Map.of("unitId", unitId("0003")), 200);
        assertThat(userIds(ok(get("/api/v1/classes/" + classId + "/participants"), admin0002, null, 200).body()))
                .doesNotContain(a1);
        JsonNode forB = ok(get("/api/v1/classes/" + classId + "/participants"), admin0003, null, 200).body();
        assertThat(userIds(forB)).contains(a1);
        // Đơn vị lúc vào lớp không đổi (dùng cho thống kê, Q4).
        assertThat(forB.get(0).get("snapshotUnit").get("code").asText()).isEqualTo("0002.0001");
    }

    @Test
    void giaoVienKhongThaoTacDuocLopKhongPhaiCuaMinh() throws Exception {
        long mine = newClass(sa);
        long notMine = newClass(sa);
        setTeachers(sa, mine, teacherId);
        long p = person(sa, "0003", "GV");
        addParticipant(teacher, mine, p);
        long pidOther = addParticipant(sa, notMine, p);
        long reason = jdbc.queryForObject("SELECT id FROM absence_reason WHERE active = 1 LIMIT 1", Long.class);

        assertThat(call(get("/api/v1/classes/" + notMine), teacher, null).status()).isEqualTo(404);
        assertThat(call(post("/api/v1/classes/" + notMine + "/participants"), teacher, Map.of("userId", plainUserId))
                .status()).isEqualTo(404);
        assertThat(call(get("/api/v1/classes/" + notMine + "/candidates").param("q", "tran"), teacher, null).status())
                .isEqualTo(404);
        assertThat(call(post("/api/v1/classes/" + notMine + "/participants/" + pidOther + "/remove"), teacher, Map.of())
                .status()).isEqualTo(404);
        assertThat(call(put("/api/v1/classes/" + notMine + "/participants/" + pidOther + "/absence"), teacher,
                Map.of("reasonId", reason)).status()).isEqualTo(404);
        // Người học của lớp khác không thao tác qua đường dẫn lớp của mình được.
        assertThat(call(post("/api/v1/classes/" + mine + "/participants/" + pidOther + "/remove"), teacher, Map.of())
                .status()).isEqualTo(404);
        // Admin không thêm trực tiếp được (dùng đề nghị).
        assertThat(call(post("/api/v1/classes/" + mine + "/participants"), admin0002, Map.of("userId", plainUserId))
                .status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ M3.2 ứng viên

    @Test
    void giaoVienTimUngVienChiNhanBonTruong() throws Exception {
        long classId = newClass(sa);
        setTeachers(sa, classId, teacherId);
        String tag = "Ứngviên" + (System.nanoTime() % 100000);
        long cand = person(sa, "0011.0001", tag);
        addParticipant(sa, classId, cand);
        person(sa, "0003", tag);

        JsonNode list = ok(get("/api/v1/classes/" + classId + "/candidates")
                .param("q", VnKey.strip(tag)), teacher, null, 200).body();
        assertThat(list.size()).isEqualTo(2);
        for (JsonNode n : list) {
            List<String> fields = new ArrayList<>();
            n.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("id", "fullName", "rankSymbol", "rankName", "unitName",
                    "personalCode", "inClass");
        }
        assertThat(list.toString()).doesNotContain("1995-05-05", "0912345678");
        JsonNode inClass = null;
        for (JsonNode n : list) {
            if (n.get("id").asLong() == cand) {
                inClass = n;
            }
        }
        assertThat(inClass).isNotNull();
        assertThat(inClass.get("inClass").asBoolean()).isTrue();

        assertThat(call(get("/api/v1/classes/" + classId + "/candidates").param("q", "a"), teacher, null).status())
                .as("ít nhất 2 ký tự").isEqualTo(400);
        // Nhiều kết quả thì cắt ở 20.
        assertThat(ok(get("/api/v1/classes/" + classId + "/candidates").param("q", "test"), teacher, null, 200).body().size())
                .isLessThanOrEqualTo(20);
        // Tìm đơn vị không dấu.
        JsonNode units = ok(get("/api/v1/classes/" + classId + "/candidates").param("q", "tham muu").param("kind", "UNIT"),
                teacher, null, 200).body();
        assertThat(units.size()).isGreaterThan(0);
        assertThat(units.get(0).has("unitId")).isTrue();

        // Lớp đóng thì không tìm được (R20).
        ok(patch("/api/v1/classes/" + classId + "/status"), sa, Map.of("status", "CLOSED"), 200);
        assertThat(call(get("/api/v1/classes/" + classId + "/candidates").param("q", "tran"), teacher, null).status())
                .isEqualTo(409);
        // Người dùng thường không phải giáo viên: 404.
        ok(patch("/api/v1/classes/" + classId + "/status"), sa, Map.of("status", "OPEN"), 200);
        assertThat(call(get("/api/v1/classes/" + classId + "/candidates").param("q", "tran"), plainUser, null).status())
                .isEqualTo(404);
    }

    // ------------------------------------------------------------------ M3.2 thêm, gỡ, lý do vắng

    @Test
    void themTrucTiepSinhSnapshotChanTrungChoThemLaiSauKhiGo() throws Exception {
        long classId = newClass(sa);
        setTeachers(sa, classId, teacherId);
        long p = person(sa, "0002.0003", "Snapshot");
        int auditBefore = countAudit(AuditAction.PARTICIPANT_ADDED, "m3c-gv");

        long pid = addParticipant(teacher, classId, p);
        assertThat(jdbc.queryForObject("SELECT unit_id_snapshot FROM class_participant WHERE id = ?", Long.class, pid))
                .isEqualTo(unitId("0002.0003"));
        assertThat(countAudit(AuditAction.PARTICIPANT_ADDED, "m3c-gv")).isEqualTo(auditBefore + 1);

        Res dup = call(post("/api/v1/classes/" + classId + "/participants"), teacher, Map.of("userId", p));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.body().get("code").asText()).isEqualTo("ALREADY_IN_CLASS");

        JsonNode removed = ok(post("/api/v1/classes/" + classId + "/participants/" + pid + "/remove"), teacher,
                Map.of(), 200).body();
        assertThat(removed.get("removed").get("at").isNull()).isFalse();
        assertThat(call(post("/api/v1/classes/" + classId + "/participants/" + pid + "/remove"), teacher, Map.of())
                .status()).isEqualTo(409);
        long again = addParticipant(teacher, classId, p);
        assertThat(again).isNotEqualTo(pid);

        // Người bị khóa và Super Admin không vào lớp được.
        long locked = ensureUser("m3c-khoa2", "USER", "0003", PW, "INACTIVE");
        assertThat(call(post("/api/v1/classes/" + classId + "/participants"), teacher, Map.of("userId", locked)).status())
                .isEqualTo(400);
        assertThat(call(post("/api/v1/classes/" + classId + "/participants"), sa, Map.of("userId", userIdOf(SA_USERNAME)))
                .status()).isEqualTo(400);
        assertThat(call(post("/api/v1/classes/" + classId + "/participants"), sa, Map.of()).status()).isEqualTo(400);
    }

    @Test
    void themDonViTuTaoBanGhiUnitMotLan() throws Exception {
        long c1 = newClass(sa);
        long c2 = newClass(sa);
        long unit = unitId("0006");
        JsonNode p1 = ok(post("/api/v1/classes/" + c1 + "/participants"), sa, Map.of("unitId", unit), 201).body();
        JsonNode p2 = ok(post("/api/v1/classes/" + c2 + "/participants"), sa, Map.of("unitId", unit), 201).body();
        assertThat(p1.get("user").get("kind").asText()).isEqualTo("UNIT");
        assertThat(p1.get("user").get("id").asLong()).isEqualTo(p2.get("user").get("id").asLong());
        assertThat(p1.get("snapshotUnit").get("id").asLong()).isEqualTo(unit);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE kind = 'UNIT' AND unit_id = ?", Integer.class,
                unit)).isEqualTo(1);
        Map<String, Object> rec = jdbc.queryForMap(
                "SELECT username, password_hash, role FROM app_user WHERE kind = 'UNIT' AND unit_id = ?", unit);
        assertThat(rec.get("username")).isNull();
        assertThat(rec.get("password_hash")).isNull();
        assertThat(rec.get("role")).isEqualTo("USER");
        // Không xuất hiện trong danh sách nhân sự mặc định.
        JsonNode users = ok(get("/api/v1/users").param("size", "100").param("unitId", String.valueOf(unit)), sa, null, 200)
                .body();
        users.get("items").forEach(n -> assertThat(n.get("kind").asText()).isEqualTo("PERSON"));
    }

    @Test
    void lyDoVangVaGoNguoiDaCoDiem() throws Exception {
        long classId = newClass(sa);
        setTeachers(sa, classId, teacherId);
        long reason = jdbc.queryForObject("SELECT id FROM absence_reason WHERE active = 1 LIMIT 1", Long.class);
        long pid = addParticipant(teacher, classId, person(sa, "0003", "Vắng"));

        JsonNode absent = ok(put("/api/v1/classes/" + classId + "/participants/" + pid + "/absence"), teacher,
                Map.of("reasonId", reason, "note", "Đi viện"), 200).body();
        assertThat(absent.get("state").asText()).isEqualTo("ABSENT");
        assertThat(absent.get("absence").get("note").asText()).isEqualTo("Đi viện");
        assertThat(call(put("/api/v1/classes/" + classId + "/participants/" + pid + "/absence"), teacher,
                Map.of("reasonId", 999999)).status()).isEqualTo(400);
        Map<String, Object> clear = new HashMap<>();
        clear.put("reasonId", null);
        JsonNode cleared = ok(put("/api/v1/classes/" + classId + "/participants/" + pid + "/absence"), teacher, clear, 200)
                .body();
        assertThat(cleared.get("state").asText()).isEqualTo("NOT_TESTED");
        assertThat(cleared.get("absence").isNull()).isTrue();

        // Đã có điểm: không ghi lý do vắng; giáo viên không gỡ được; Super Admin gỡ phải có lý do, điểm giữ lại.
        insertResult(pid, 65);
        assertThat(call(put("/api/v1/classes/" + classId + "/participants/" + pid + "/absence"), teacher,
                Map.of("reasonId", reason)).status()).isEqualTo(409);
        assertThat(call(post("/api/v1/classes/" + classId + "/participants/" + pid + "/remove"), teacher,
                Map.of("reason", "x")).status()).isEqualTo(403);
        assertThat(call(post("/api/v1/classes/" + classId + "/participants/" + pid + "/remove"), sa, Map.of()).status())
                .isEqualTo(400);
        ok(post("/api/v1/classes/" + classId + "/participants/" + pid + "/remove"), sa,
                Map.of("reason", "Nhập nhầm người"), 200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM result WHERE participant_id = ?", Integer.class, pid))
                .as("điểm giữ lại").isEqualTo(1);

        // Bộ lọc "đã gỡ" chỉ Super Admin.
        JsonNode removed = ok(get("/api/v1/classes/" + classId + "/participants").param("state", "REMOVED"), sa, null, 200)
                .body();
        assertThat(ids(removed)).containsExactly(pid);
        assertThat(removed.get(0).get("removed").get("reason").asText()).isEqualTo("Nhập nhầm người");
        assertThat(removed.get(0).get("result").get("scoreX10").asInt()).isEqualTo(65);
        assertThat(call(get("/api/v1/classes/" + classId + "/participants").param("state", "REMOVED"), teacher, null)
                .status()).isEqualTo(403);
        assertThat(ok(get("/api/v1/classes/" + classId + "/participants"), teacher, null, 200).body()).isEmpty();
    }

    @Test
    void userUnitKhongDangNhapDuoc() throws Exception {
        long classId = newClass(sa);
        ok(post("/api/v1/classes/" + classId + "/participants"), sa, Map.of("unitId", unitId("0007")), 201);
        // Bản ghi đơn vị không có tài khoản nên không có tên nào để đăng nhập; thử với tên đơn vị cũng bị từ chối.
        assertThat(loginResult("0007", PW).getResponse().getStatus()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE kind = 'UNIT' AND username IS NOT NULL",
                Integer.class)).isZero();
        // Không cấp quyền Admin, không đặt mật khẩu, không sửa được qua API người dùng (R19).
        long unitRecord = jdbc.queryForObject("SELECT id FROM app_user WHERE kind = 'UNIT' AND unit_id = ?", Long.class,
                unitId("0007"));
        Res role = call(put("/api/v1/users/" + unitRecord + "/role"), sa, Map.of("role", "ADMIN"));
        assertThat(role.status()).isEqualTo(400);
        assertThat(role.body().get("code").asText()).isEqualTo("UNIT_RECORD");
        assertThat(call(post("/api/v1/users/" + unitRecord + "/reset-password"), sa, Map.of("newPassword", "MatKhau@123"))
                .status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE id = ?", String.class, unitRecord)).isEqualTo("USER");
    }

    // ------------------------------------------------------------------ tiện ích

    @Test
    void danhSachCoTienDoChamVaLocNoiDungChuaChamXong() throws Exception {
        long classId = newClass(sa);
        setTeachers(sa, classId, teacherId);
        long period = ok(get("/api/v1/classes/" + classId), sa, null, 200).body().get("period").get("id").asLong();
        long done = newClassInPeriod(period);
        long reason = jdbc.queryForObject("SELECT id FROM absence_reason WHERE active = 1 LIMIT 1", Long.class);
        long p1 = addParticipant(teacher, classId, person(sa, "0002.0001", "ĐãChấm"));
        long p2 = addParticipant(teacher, classId, person(sa, "0002.0001", "Vắng"));
        addParticipant(teacher, classId, person(sa, "0002.0001", "ChưaChấm"));
        addParticipant(teacher, classId, person(sa, "0003", "ChưaChấmB"));
        insertResult(p1, 70);
        ok(put("/api/v1/classes/" + classId + "/participants/" + p2 + "/absence"), teacher, Map.of("reasonId", reason), 200);
        insertResult(addParticipant(sa, done, person(sa, "0002", "XongHết")), 80);

        // Giám khảo, Super Admin: cả lớp. 4 người, 1 có điểm, 1 vắng có lý do, còn 2 phải chấm.
        JsonNode t = item(teacher, period, classId, false);
        assertThat(t.get("participantCount").asInt()).isEqualTo(4);
        assertThat(t.get("gradedCount").asInt()).isEqualTo(1);
        assertThat(t.get("pendingCount").asInt()).isEqualTo(2);
        assertThat(item(sa, period, done, false).get("pendingCount").asInt()).isZero();
        // Admin: chỉ đếm người trong nhánh.
        JsonNode a = item(admin0002, period, classId, false);
        assertThat(a.get("participantCount").asInt()).isEqualTo(3);
        assertThat(a.get("pendingCount").asInt()).isEqualTo(1);
        assertThat(item(admin0004, period, classId, false).get("pendingCount").asInt()).isZero();

        // Bộ lọc "chưa chấm xong": lớp đã chấm đủ và lớp không còn ai trong nhánh phải chấm bị loại.
        assertThat(item(sa, period, classId, true)).isNotNull();
        assertThat(item(sa, period, done, true)).isNull();
        assertThat(item(admin0002, period, classId, true)).isNotNull();
        assertThat(item(admin0004, period, classId, true)).isNull();
        assertThat(ok(get("/api/v1/classes").param("periodId", String.valueOf(period)).param("ungraded", "true"), sa, null, 200)
                .body().get("total").asInt()).isEqualTo(1);
    }

    @Test
    void khoaDotThiMoiNoiDungCuaDotDeuDong() throws Exception {
        long classId = newClass(sa);
        setTeachers(sa, classId, teacherId);
        long period = ok(get("/api/v1/classes/" + classId), sa, null, 200).body().get("period").get("id").asLong();
        long closedBefore = newClassInPeriod(period);
        ok(patch("/api/v1/classes/" + closedBefore + "/status"), sa, Map.of("status", "CLOSED"), 200);
        long inside = person(sa, "0002.0001", "ĐợtKhóa");

        ok(patch("/api/v1/periods/" + period + "/status"), sa, Map.of("status", "CLOSED"), 200);

        // Mọi nơi trả trạng thái lớp đều thấy Đã đóng.
        assertThat(ok(get("/api/v1/classes/" + classId), sa, null, 200).body().get("status").asText()).isEqualTo("CLOSED");
        assertThat(ok(get("/api/v1/classes/" + classId), admin0002, null, 200).body().get("access").get("request").asBoolean())
                .isFalse();
        assertThat(item(sa, period, classId, false).get("status").asText()).isEqualTo("CLOSED");
        JsonNode open = ok(get("/api/v1/classes").param("periodId", String.valueOf(period)).param("status", "OPEN"), sa, null, 200).body();
        assertThat(open.get("total").asInt()).isZero();
        assertThat(ids(ok(get("/api/v1/classes/open"), admin0002, null, 200).body())).doesNotContain(classId);
        // Không thêm người, không đề nghị được; không mở lại riêng lớp khi đợt còn khóa.
        Res add = call(post("/api/v1/classes/" + classId + "/participants"), teacher, Map.of("userId", inside));
        assertThat(add.status()).isEqualTo(409);
        assertThat(add.body().get("code").asText()).isEqualTo("CLASS_CLOSED");
        assertThat(call(post("/api/v1/classes/" + classId + "/requests"), admin0002, Map.of("type", "ADD", "userId", inside))
                .status()).isEqualTo(409);
        Res reopen = call(patch("/api/v1/classes/" + classId + "/status"), sa, Map.of("status", "OPEN"));
        assertThat(reopen.status()).isEqualTo(409);
        assertThat(reopen.body().get("code").asText()).isEqualTo("PERIOD_CLOSED");

        // Mở lại đợt: lớp trở về trạng thái riêng (lớp đã đóng trước đó vẫn đóng).
        ok(patch("/api/v1/periods/" + period + "/status"), sa, Map.of("status", "OPEN"), 200);
        assertThat(ok(get("/api/v1/classes/" + classId), sa, null, 200).body().get("status").asText()).isEqualTo("OPEN");
        assertThat(ok(get("/api/v1/classes/" + closedBefore), sa, null, 200).body().get("status").asText()).isEqualTo("CLOSED");
        assertThat(call(post("/api/v1/classes/" + classId + "/participants"), teacher, Map.of("userId", inside)).status())
                .isEqualTo(201);
    }

    private long newClassInPeriod(long periodId) throws Exception {
        return ok(post("/api/v1/classes"), sa, Map.of("periodId", periodId, "name", uniq("Lớp xong"),
                "examDate", "2026-11-21"), 201).id();
    }

    /** Dòng của lớp trong GET /classes (lọc theo đợt, có thể chỉ lớp chưa chấm xong), null nếu không có. */
    private JsonNode item(MockHttpSession s, long periodId, long classId, boolean ungraded) throws Exception {
        var req = get("/api/v1/classes").param("size", "100").param("periodId", String.valueOf(periodId));
        if (ungraded) {
            req = req.param("ungraded", "true");
        }
        for (JsonNode n : ok(req, s, null, 200).body().get("items")) {
            if (n.get("id").asLong() == classId) {
                return n;
            }
        }
        return null;
    }

    /** Số người học hiển thị trong GET /classes cho lớp, -1 nếu lớp không có trong danh sách. */
    private int countInList(MockHttpSession s, long classId) throws Exception {
        // Lọc theo đợt của lớp: Admin thấy mọi lớp nên trang đầu của toàn bộ danh sách có thể không chứa lớp cần tìm.
        String periodId = ok(get("/api/v1/classes/" + classId), sa, null, 200).body().get("period").get("id").asText();
        JsonNode items = ok(get("/api/v1/classes").param("size", "100").param("periodId", periodId), s, null, 200)
                .body().get("items");
        for (JsonNode n : items) {
            if (n.get("id").asLong() == classId) {
                return n.get("participantCount").asInt();
            }
        }
        return -1;
    }

    private void insertResult(long participantId, int scoreX10) {
        long saId = userIdOf(SA_USERNAME);
        tx().executeWithoutResult(s -> jdbc.update(
                "INSERT INTO result (participant_id, score_x10, graded_by) VALUES (?, ?, ?)", participantId, scoreX10, saId));
    }

    /** Bỏ dấu giống search_text để gõ truy vấn trong test. */
    static final class VnKey {
        static String strip(String s) {
            return VietnameseText.searchKey(s);
        }
    }
}
