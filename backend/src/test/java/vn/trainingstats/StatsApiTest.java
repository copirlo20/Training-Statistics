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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

/**
 * M6.1: API thống kê. Mỗi test tạo đợt riêng và lọc theo đợt đó, nên không lẫn dữ liệu của test khác.
 * Cây: 0002 có con 0002.0001, 0002.0003; 0003, 0004, 0006 là đơn vị cấp 1 khác.
 */
class StatsApiTest extends M3TestBase {

    private MockHttpSession sa;
    private MockHttpSession admin0002;
    private MockHttpSession admin0003;
    private MockHttpSession teacher;
    private MockHttpSession otherTeacher;
    private MockHttpSession plain;
    private long teacherId;

    @BeforeEach
    void setUp() throws Exception {
        ensureUser("m6-admin-0002", "ADMIN", "0002", PW, "ACTIVE");
        ensureUser("m6-admin-0003", "ADMIN", "0003", PW, "ACTIVE");
        teacherId = ensureUser("m6-gv", "USER", "0004", PW, "ACTIVE");
        ensureUser("m6-gv-khac", "USER", "0004", PW, "ACTIVE");
        ensureUser("m6-user", "USER", "0002.0001", PW, "ACTIVE");
        sa = login(SA_USERNAME, SA_PASSWORD);
        admin0002 = login("m6-admin-0002", PW);
        admin0003 = login("m6-admin-0003", PW);
        teacher = login("m6-gv", PW);
        otherTeacher = login("m6-gv-khac", PW);
        plain = login("m6-user", PW);
    }

    private long periodOf(long classId) {
        return jdbc.queryForObject("SELECT period_id FROM exam_class WHERE id = ?", Long.class, classId);
    }

    private void grade(long classId, long pid, String score) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("score", new BigDecimal(score));
        ok(post("/api/v1/classes/" + classId + "/participants/" + pid + "/result"), sa, body, 201);
    }

    private JsonNode stats(String path, MockHttpSession s, Object... params) throws Exception {
        var b = get("/api/v1/stats/" + path);
        for (int i = 0; i < params.length; i += 2) {
            b = b.param((String) params[i], String.valueOf(params[i + 1]));
        }
        return ok(b, s, null, 200).body();
    }

    private static JsonNode row(JsonNode rows, String code) {
        for (JsonNode r : rows) {
            if (r.get("code").asText().equals(code)) {
                return r;
            }
        }
        return null;
    }

    private static List<String> codes(JsonNode rows) {
        List<String> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.get("code").asText()));
        return out;
    }

    // ------------------------------------------------------------------ R2, Q4, Q9

    @Test
    void chuyenDonViKhongDoiThongKeCuVaBoNguoiDaGo() throws Exception {
        long classId = newClass(sa);
        long period = periodOf(classId);
        long a = person(sa, "0002.0001", "Chuyển");
        long pa = addParticipant(sa, classId, a);
        long pb = addParticipant(sa, classId, person(sa, "0003", "Bình"));
        long pc = addParticipant(sa, classId, person(sa, "0002.0001", "Gỡ"));
        grade(classId, pa, "8.5");
        grade(classId, pb, "4.9");
        grade(classId, pc, "10");
        ok(post("/api/v1/classes/" + classId + "/participants/" + pc + "/remove"), sa, Map.of("reason", "Nhầm lớp"), 200);

        JsonNode before = stats("grade-by-unit", sa, "periodId", period);
        ok(post("/api/v1/users/" + a + "/move"), sa, Map.of("unitId", unitId("0003")), 200);
        JsonNode after = stats("grade-by-unit", sa, "periodId", period);
        assertThat(after.get("rows")).as("chuyển đơn vị không đổi số liệu cũ").isEqualTo(before.get("rows"));

        assertThat(codes(after.get("rows"))).containsExactly("0002.0001", "0003");
        JsonNode u1 = row(after.get("rows"), "0002.0001").get("counts");
        assertThat(u1.get("total").asLong()).as("người đã gỡ không tính").isEqualTo(1);
        assertThat(u1.get("excellent").asLong()).isEqualTo(1);
        assertThat(u1.get("maxX10").asInt()).as("điểm 10 của người đã gỡ không tính").isEqualTo(85);
        JsonNode u3 = row(after.get("rows"), "0003").get("counts");
        assertThat(u3.get("fail").asLong()).isEqualTo(1);
        assertThat(after.get("totals").get("total").asLong()).isEqualTo(2);

        JsonNode top = stats("grade-by-unit", sa, "periodId", period, "group", "top");
        assertThat(codes(top.get("rows"))).as("gộp về đơn vị cấp 1").containsExactly("0002", "0003");

        // Phạm vi theo đơn vị lúc vào lớp: Admin 0002 vẫn thấy người đã chuyển sang 0003 trong số liệu cũ.
        JsonNode mine = stats("grade-by-unit", admin0002, "periodId", period);
        assertThat(codes(mine.get("rows"))).containsExactly("0002.0001");
        assertThat(mine.get("totals").get("total").asLong()).isEqualTo(1);
        JsonNode other = stats("grade-by-unit", admin0003, "periodId", period);
        assertThat(codes(other.get("rows"))).containsExactly("0003");
    }

    @Test
    void nguoiDungXoaMemKhongHienTrongDanhSachNhungConTrongThongKeLichSu() throws Exception {
        long classId = newClass(sa);
        long period = periodOf(classId);
        long deleted = person(sa, "0002.0001", "Xóa mềm");
        long kept = person(sa, "0002.0001", "Giữ");
        grade(classId, addParticipant(sa, classId, deleted), "9");
        grade(classId, addParticipant(sa, classId, kept), "4.9");
        JsonNode before = stats("grade-by-unit", sa, "periodId", period);

        ok(patch("/api/v1/users/" + deleted + "/status"),
                sa, Map.of("status", "DELETED"), 200);

        Res list = call(get("/api/v1/users").param("size", "100").param("q", "Xóa mềm"), sa, null);
        assertThat(list.body().get("items").findValues("id").stream().map(JsonNode::asLong).toList())
                .as("không hiện trong danh sách mặc định").doesNotContain(deleted);
        Res onlyDeleted = call(get("/api/v1/users").param("size", "100").param("q", "Xóa mềm").param("status", "DELETED"), sa, null);
        assertThat(onlyDeleted.body().get("items").findValues("id").stream().map(JsonNode::asLong).toList())
                .as("lọc theo trạng thái đã xóa thì thấy").contains(deleted);
        JsonNode after = stats("grade-by-unit", sa, "periodId", period);
        assertThat(after.get("rows")).as("thống kê lịch sử không đổi").isEqualTo(before.get("rows"));
        assertThat(after.get("totals").get("total").asLong()).isEqualTo(2);
        assertThat(after.get("totals").get("excellent").asLong()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ R9

    @Test
    void diemCaNhanVaDiemDonViKhongTron() throws Exception {
        long classId = newClass(sa);
        long period = periodOf(classId);
        long person = addParticipant(sa, classId, person(sa, "0006", "Cá nhân"));
        long unit = ok(post("/api/v1/classes/" + classId + "/participants"), sa, Map.of("unitId", unitId("0006")), 201).id();
        grade(classId, person, "7");
        grade(classId, unit, "9");

        JsonNode p = stats("grade-by-unit", sa, "periodId", period);
        assertThat(p.get("kind").asText()).as("mặc định PERSON").isEqualTo("PERSON");
        assertThat(p.get("totals").get("total").asLong()).isEqualTo(1);
        assertThat(p.get("totals").get("avgX10").asDouble()).isEqualTo(70.0);
        JsonNode u = stats("grade-by-unit", sa, "periodId", period, "kind", "UNIT");
        assertThat(u.get("totals").get("total").asLong()).isEqualTo(1);
        assertThat(u.get("totals").get("avgX10").asDouble()).isEqualTo(90.0);
        JsonNode cls = stats("class/" + classId, sa);
        assertThat(cls.get("counts").get("total").asLong()).isEqualTo(1);
        assertThat(stats("class/" + classId, sa, "kind", "UNIT").get("counts").get("excellent").asLong()).isEqualTo(1);

        assertThat(call(get("/api/v1/stats/grade-by-unit").param("kind", "ALL"), sa, null).status()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ xếp loại, điểm theo đơn vị

    @Test
    void demXepLoaiTheoCotDbOCacDiemBienVaPhanBoDiem() throws Exception {
        long classId = newClass(sa);
        long period = periodOf(classId);
        String[] scores = {"4.9", "5.0", "6.5", "6.6", "7.9", "8.0", "10"};
        for (String s : scores) {
            grade(classId, addParticipant(sa, classId, person(sa, "0003", "Biên")), s);
        }
        addParticipant(sa, classId, person(sa, "0003", "Chưa"));
        JsonNode t = stats("grade-by-unit", sa, "periodId", period).get("totals");
        assertThat(t.get("total").asLong()).isEqualTo(8);
        assertThat(t.get("tested").asLong()).isEqualTo(7);
        assertThat(t.get("notTested").asLong()).isEqualTo(1);
        assertThat(t.get("fail").asLong()).isEqualTo(1);
        assertThat(t.get("pass").asLong()).isEqualTo(2);
        assertThat(t.get("good").asLong()).isEqualTo(2);
        assertThat(t.get("excellent").asLong()).isEqualTo(2);
        assertThat(t.get("minX10").asInt()).isEqualTo(49);
        assertThat(t.get("maxX10").asInt()).isEqualTo(100);
        assertThat(t.get("avgX10").asDouble()).isEqualTo(Math.round((49 + 50 + 65 + 66 + 79 + 80 + 100) / 7.0 * 10) / 10.0);

        JsonNode h = stats("class/" + classId, sa).get("histogram");
        assertThat(h.size()).isEqualTo(10);
        long[] expected = {0, 0, 0, 0, 1, 1, 2, 1, 1, 1};
        for (int i = 0; i < 10; i++) {
            assertThat(h.get(i).get("count").asLong()).as("khoảng %d", i).isEqualTo(expected[i]);
        }

        // unit-scores: xếp theo điểm trung bình, cao trước.
        long c2 = ok(post("/api/v1/classes"), sa, Map.of("periodId", period,
                "name", uniq("Nội dung thử"), "examDate", "2026-11-21"), 201).id();
        grade(c2, addParticipant(sa, c2, person(sa, "0004", "Cao")), "9.5");
        JsonNode us = stats("unit-scores", sa, "periodId", period);
        assertThat(codes(us.get("rows"))).containsExactly("0004", "0003");

        // Lọc theo khoảng ngày kiểm tra và theo lớp.
        assertThat(stats("grade-by-unit", sa, "periodId", period, "from", "2026-11-21").get("totals").get("total")
                .asLong()).isEqualTo(1);
        assertThat(stats("grade-by-unit", sa, "periodId", period, "to", "2026-11-20").get("totals").get("total")
                .asLong()).isEqualTo(8);
        assertThat(stats("grade-by-unit", sa, "classId", c2).get("totals").get("total").asLong()).isEqualTo(1);
        assertThat(call(get("/api/v1/stats/grade-by-unit").param("from", "2026-11-22").param("to", "2026-11-01"), sa,
                null).status()).isEqualTo(400);
        assertThat(call(get("/api/v1/stats/grade-by-unit").param("from", "22/13/2026"), sa, null).status())
                .isEqualTo(400);
    }

    // ------------------------------------------------------------------ phạm vi

    @Test
    void gioiHanTheoPhamViVaDonViNgoaiPhamVi404() throws Exception {
        long classId = newClass(sa);
        long period = periodOf(classId);
        grade(classId, addParticipant(sa, classId, person(sa, "0002.0003", "Trong")), "7");
        grade(classId, addParticipant(sa, classId, person(sa, "0003", "Ngoài")), "7");

        JsonNode byUnit = stats("grade-by-unit", admin0002, "periodId", period, "unitId", unitId("0002"));
        assertThat(codes(byUnit.get("rows"))).as("lọc đơn vị gồm đơn vị con").containsExactly("0002.0003");
        assertThat(call(get("/api/v1/stats/grade-by-unit").param("unitId", String.valueOf(unitId("0003"))), admin0002,
                null).status()).isEqualTo(404);
        assertThat(call(get("/api/v1/stats/overview").param("unitId", "999999"), sa, null).status()).isEqualTo(404);
        // Nhiều đơn vị (2026-11-22): gộp các nhánh; một đơn vị ngoài phạm vi trong danh sách thì cả yêu cầu 404.
        JsonNode both = stats("grade-by-unit", sa, "periodId", period, "unitId", unitId("0002") + "," + unitId("0003"));
        assertThat(codes(both.get("rows"))).as("lọc hai đơn vị").containsExactlyInAnyOrder("0002.0003", "0003");
        assertThat(stats("overview", sa, "periodId", period, "unitId", unitId("0002.0003") + "," + unitId("0003"))
                .get("totals").get("total").asLong()).isEqualTo(2);
        assertThat(call(get("/api/v1/stats/overview").param("unitId", unitId("0002") + "," + unitId("0003")), admin0002,
                null).status()).isEqualTo(404);

        JsonNode overview = stats("overview", admin0002, "periodId", period);
        assertThat(overview.get("totals").get("total").asLong()).isEqualTo(1);
        assertThat(overview.get("byPeriod").size()).isEqualTo(1);
        assertThat(overview.get("byPeriod").get(0).get("periodId").asLong()).isEqualTo(period);
        assertThat(overview.get("byContent").get(0).get("counts").get("good").asLong()).isEqualTo(1);
        assertThat(overview.has("bySubject")).as("không còn thống kê theo môn").isFalse();
        assertThat(overview.get("byContent").findValues("tested").stream().allMatch(n -> n.asLong() > 0))
                .as("chỉ nội dung đã có điểm").isTrue();

        for (String path : new String[] {"overview", "grade-by-unit", "unit-scores", "not-tested"}) {
            assertThat(call(get("/api/v1/stats/" + path), plain, null).status()).as(path).isEqualTo(403);
        }
    }

    @Test
    void thongKeLopChoGiaoVienCuaLopVaAdminTheoNhanh() throws Exception {
        long classId = newClass(sa);
        setTeachers(sa, classId, teacherId);
        grade(classId, addParticipant(sa, classId, person(sa, "0002.0001", "Một")), "6");
        grade(classId, addParticipant(sa, classId, person(sa, "0003", "Hai")), "8");

        JsonNode t = stats("class/" + classId, teacher);
        assertThat(t.get("counts").get("total").asLong()).as("giáo viên xem cả lớp").isEqualTo(2);
        assertThat(t.get("classInfo").get("id").asLong()).isEqualTo(classId);
        assertThat(stats("class/" + classId, sa).get("counts").get("total").asLong()).isEqualTo(2);
        JsonNode a = stats("class/" + classId, admin0002);
        assertThat(a.get("counts").get("total").asLong()).as("Admin chỉ thấy người thuộc nhánh").isEqualTo(1);
        assertThat(codes(a.get("byUnit"))).containsExactly("0002.0001");

        assertThat(call(get("/api/v1/stats/class/" + classId), otherTeacher, null).status()).isEqualTo(404);
        assertThat(call(get("/api/v1/stats/class/" + classId), plain, null).status()).isEqualTo(404);
        assertThat(call(get("/api/v1/stats/class/999999"), sa, null).status()).isEqualTo(404);
        long otherClass = newClass(sa);
        addParticipant(sa, otherClass, person(sa, "0006", "Xa"));
        assertThat(stats("class/" + otherClass, admin0002).get("counts").get("total").asLong())
                .as("lớp mở Admin xem được nhưng không có người thuộc nhánh").isZero();
        ok(patch(
                "/api/v1/classes/" + otherClass + "/status"), sa, Map.of("status", "CLOSED"), 200);
        assertThat(stats("class/" + otherClass, admin0002).get("counts").get("total").asLong())
                .as("lớp đóng không có người học trong nhánh: Admin vẫn xem được, số liệu rỗng").isZero();
    }

    // ------------------------------------------------------------------ chưa kiểm tra

    @Test
    void chuaKiemTraKemLyDoVaChiHienNguoiDangThuocNhanh() throws Exception {
        long classId = newClass(sa);
        long period = periodOf(classId);
        long reasonId = jdbc.queryForObject("SELECT id FROM absence_reason WHERE active = 1 ORDER BY id LIMIT 1", Long.class);
        String reason = jdbc.queryForObject("SELECT name FROM absence_reason WHERE id = ?", String.class, reasonId);
        long absent = addParticipant(sa, classId, person(sa, "0002.0001", "Vắng"));
        ok(put("/api/v1/classes/" + classId + "/participants/" + absent + "/absence"), sa,
                Map.of("reasonId", reasonId, "note", "Đi viện"), 200);
        long moved = person(sa, "0002.0001", "Chuyển");
        addParticipant(sa, classId, moved);
        addParticipant(sa, classId, person(sa, "0003", "Khác"));
        grade(classId, addParticipant(sa, classId, person(sa, "0002.0001", "Đã thi")), "7");

        JsonNode all = stats("not-tested", sa, "periodId", period);
        assertThat(all.get("total").asLong()).isEqualTo(3);
        assertThat(all.get("absent").asLong()).isEqualTo(1);
        assertThat(all.get("notTested").asLong()).isEqualTo(2);
        assertThat(all.get("reasons").get(0).get("reason").asText()).isEqualTo(reason);
        JsonNode onlyAbsent = stats("not-tested", sa, "periodId", period, "state", "ABSENT");
        assertThat(onlyAbsent.get("items").size()).isEqualTo(1);
        JsonNode item = onlyAbsent.get("items").get(0);
        assertThat(item.get("state").asText()).isEqualTo("ABSENT");
        assertThat(item.get("reasonName").asText()).isEqualTo(reason);
        assertThat(item.get("note").asText()).isEqualTo("Đi viện");
        assertThat(stats("not-tested", sa, "periodId", period, "size", 2).get("items").size()).isEqualTo(2);
        assertThat(call(get("/api/v1/stats/not-tested").param("state", "X"), sa, null).status()).isEqualTo(400);

        assertThat(stats("not-tested", admin0002, "periodId", period).get("total").asLong()).isEqualTo(2);
        ok(post("/api/v1/users/" + moved + "/move"), sa, Map.of("unitId", unitId("0003")), 200);
        JsonNode after = stats("not-tested", admin0002, "periodId", period);
        assertThat(after.get("total").asLong()).as("không lộ họ tên người đã chuyển khỏi nhánh").isEqualTo(1);
        assertThat(after.get("items").get(0).get("participantId").asLong()).isEqualTo(absent);
        assertThat(stats("not-tested", admin0003, "periodId", period).get("total").asLong())
                .as("nhánh mới cũng không thấy lượt học ở đơn vị cũ").isEqualTo(1);
    }

    // ------------------------------------------------------------------ hiệu năng

    @Test
    void hieuNangVoiHaiNghinNguoiVaNhieuLop() throws Exception {
        long period = period(sa);
        long saId = userIdOf(SA_USERNAME);
        List<Long> unitIds = jdbc.queryForList("SELECT id FROM unit ORDER BY id", Long.class);
        String tag = uniq("perf");
        tx().executeWithoutResult(s -> {
            List<Object[]> users = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                users.add(new Object[] {"Người " + i, "nguoi " + i, unitIds.get(i % unitIds.size()), tag + "-" + i});
            }
            jdbc.batchUpdate("""
                    INSERT INTO app_user (kind, last_name, first_name, search_text, unit_id, role, status, personal_code)
                    VALUES ('PERSON', 'Hiệu năng', ?, ?, ?, 'USER', 'ACTIVE', ?)
                    """, users);
            List<Long[]> people = jdbc.query("SELECT id, unit_id FROM app_user WHERE personal_code LIKE ?",
                    (rs, i) -> new Long[] {rs.getLong(1), rs.getLong(2)}, tag + "-%");
            for (int c = 0; c < 40; c++) {
                jdbc.update("INSERT INTO exam_class (period_id, name, exam_date) VALUES (?, ?, ?)",
                        period, tag + " nội dung " + c, "2026-11-" + (10 + c % 18));
                long classId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
                List<Object[]> parts = new ArrayList<>();
                for (int k = 0; k < 250; k++) {
                    Long[] p = people.get((c * 250 + k) % people.size());
                    parts.add(new Object[] {classId, p[0], p[1]});
                }
                jdbc.batchUpdate("INSERT INTO class_participant (class_id, user_id, unit_id_snapshot) VALUES (?, ?, ?)",
                        parts);
                jdbc.update("""
                        INSERT INTO result (participant_id, score_x10, graded_by)
                        SELECT id, (id * 7) % 101, ? FROM class_participant WHERE class_id = ? AND id % 5 <> 0
                        """, saId, classId);
            }
        });

        stats("overview", sa, "periodId", period);
        long start = System.nanoTime();
        JsonNode o = stats("overview", sa, "group", "top");
        stats("grade-by-unit", sa);
        stats("unit-scores", admin0002);
        stats("not-tested", sa, "periodId", period);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(o.get("totals").get("total").asLong()).isGreaterThanOrEqualTo(10_000);
        assertThat(stats("overview", sa, "periodId", period).get("totals").get("total").asLong()).isEqualTo(10_000);
        assertThat(ms).as("4 truy vấn thống kê trên 10.000 lượt học (ms)").isLessThan(3000);
    }
}
