package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

/**
 * Bảng phân công (người x nội dung kiểm tra của một đợt): phạm vi danh sách, cột theo vai trò, bộ lọc, thêm trực tiếp
 * của Super Admin và giám khảo, đề nghị của Admin kèm một thông báo gộp.
 * Cây dùng: 0002 (Phòng Tham mưu) có con 0002.0001; 0003 là nhánh khác; 0008 là nơi giám khảo thuộc về.
 */
class AssignmentApiTest extends M3TestBase {

    private MockHttpSession sa;
    private MockHttpSession admin0002;
    private MockHttpSession admin0003;
    private MockHttpSession teacher;
    private MockHttpSession plain;
    private long teacherId;
    private long periodId;
    private long c1;   // giám khảo là teacher
    private long c2;   // không có giám khảo
    private String tag;
    private long pTop;     // đơn vị 0002
    private long pChild;   // đơn vị 0002.0001
    private long pOther;   // đơn vị 0003

    @BeforeEach
    void setUp() throws Exception {
        ensureUser("m7a-admin-0002", "ADMIN", "0002", PW, "ACTIVE");
        ensureUser("m7a-admin-0003", "ADMIN", "0003", PW, "ACTIVE");
        teacherId = ensureUser("m7a-gv", "USER", "0008", PW, "ACTIVE");
        ensureUser("m7a-user", "USER", "0002.0002", PW, "ACTIVE");
        sa = login(SA_USERNAME, SA_PASSWORD);
        admin0002 = login("m7a-admin-0002", PW);
        admin0003 = login("m7a-admin-0003", PW);
        teacher = login("m7a-gv", PW);
        plain = login("m7a-user", PW);

        periodId = period(sa);
        c1 = newClassIn(periodId, "Bắn súng");
        c2 = newClassIn(periodId, "Thể lực");
        setTeachers(sa, c1, teacherId);
        tag = "Pc" + System.nanoTime();
        pTop = person(sa, "0002", tag + "A");
        pChild = person(sa, "0002.0001", tag + "B");
        pOther = person(sa, "0003", tag + "C");
    }

    private long newClassIn(long period, String name) throws Exception {
        return ok(post("/api/v1/classes"), sa, Map.of("periodId", period, "name", uniq(name), "examDate", "2026-11-20"), 201).id();
    }

    private JsonNode matrix(MockHttpSession s, String... params) throws Exception {
        var b = get("/api/v1/assignments/matrix").param("periodId", String.valueOf(periodId));
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return ok(b, s, null, 200).body();
    }

    private static List<Long> rowIds(JsonNode m) {
        List<Long> out = new ArrayList<>();
        m.get("rows").forEach(r -> out.add(r.get("userId").asLong()));
        return out;
    }

    private static JsonNode row(JsonNode m, long userId) {
        for (JsonNode r : m.get("rows")) {
            if (r.get("userId").asLong() == userId) {
                return r;
            }
        }
        return null;
    }

    private static String cell(JsonNode m, long userId, long classId) {
        JsonNode r = row(m, userId);
        return r == null || !r.get("cells").has(String.valueOf(classId)) ? null : r.get("cells").get(String.valueOf(classId)).asText();
    }

    private static String mode(JsonNode m, long classId) {
        for (JsonNode c : m.get("columns")) {
            if (c.get("id").asLong() == classId) {
                return c.get("mode").asText();
            }
        }
        return null;
    }

    private JsonNode apply(MockHttpSession s, Object... triples) throws Exception {
        List<Map<String, Object>> changes = new ArrayList<>();
        for (int i = 0; i < triples.length; i += 3) {
            changes.add(Map.of("classId", triples[i], "userId", triples[i + 1], "action", triples[i + 2]));
        }
        return ok(post("/api/v1/assignments/apply"), s, Map.of("changes", changes), 200).body();
    }

    // ------------------------------------------------------------------ phạm vi danh sách và cột

    @Test
    void superAdminThayToanBoDanhSachVaMoiCot() throws Exception {
        JsonNode m = matrix(sa, "q", tag);
        assertThat(m.get("viewer").get("fullList").asBoolean()).isTrue();
        assertThat(rowIds(m)).containsExactly(pTop, pChild, pOther);   // theo thứ tự cây đơn vị: 0002, 0002.0001, 0003
        assertThat(mode(m, c1)).isEqualTo("DIRECT");
        assertThat(mode(m, c2)).isEqualTo("DIRECT");
        JsonNode r = row(m, pChild);
        assertThat(r.get("unitName").asText()).isEqualTo("Ban Quân huấn");
        assertThat(r.get("parentUnitName").asText()).isEqualTo("Phòng Tham mưu");
        // Chỉ các trường được phép: không ngày sinh, số điện thoại, tài khoản.
        Set<String> fields = new TreeSet<>();
        r.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("userId", "fullName", "rankSymbol", "personalCode", "category", "unitId",
                "unitCode", "unitName", "parentUnitName", "specialties", "cells");
    }

    @Test
    void xemTungDonViMotVaLocDoiTuong() throws Exception {
        // unit: đúng một đơn vị, không gồm đơn vị con (giao diện xem từng đơn vị một).
        assertThat(rowIds(matrix(sa, "q", tag, "unit", String.valueOf(unitId("0002"))))).containsExactly(pTop);
        assertThat(rowIds(matrix(sa, "q", tag, "unit", String.valueOf(unitId("0002.0001"))))).containsExactly(pChild);
        assertThat(rowIds(matrix(admin0002, "q", tag, "unit", String.valueOf(unitId("0002.0001"))))).containsExactly(pChild);
        assertThat(call(get("/api/v1/assignments/matrix").param("periodId", String.valueOf(periodId))
                .param("unit", String.valueOf(unitId("0003"))), admin0002, null).status()).as("ngoài nhánh").isEqualTo(404);

        // Đối tượng: người tạo bằng person() là SQ.
        long q = person(sa, "0002", tag + "Q");
        tx().executeWithoutResult(st -> jdbc.update("UPDATE app_user SET category = 'QNCN' WHERE id = ?", q));
        assertThat(rowIds(matrix(sa, "q", tag, "category", "QNCN"))).containsExactly(q);
        assertThat(rowIds(matrix(sa, "q", tag, "category", "SQ,QNCN"))).containsExactlyInAnyOrder(pTop, pChild, pOther, q);
        assertThat(row(matrix(sa, "q", tag), q).get("category").asText()).isEqualTo("QNCN");
        assertThat(call(get("/api/v1/assignments/matrix").param("periodId", String.valueOf(periodId))
                .param("category", "ABC"), sa, null).status()).isEqualTo(400);

        // Danh sách đơn vị có số người thuộc đúng đơn vị đó; Admin chỉ thấy đơn vị trong nhánh.
        JsonNode units = ok(get("/api/v1/assignments/units").param("periodId", String.valueOf(periodId)), admin0002, null, 200).body();
        List<String> codes = new ArrayList<>();
        units.forEach(u -> codes.add(u.get("code").asText()));
        assertThat(codes).contains("0002", "0002.0001").noneMatch(c -> c.startsWith("0003"));
        int direct = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE unit_id = ? AND kind = 'PERSON' AND status = 'ACTIVE'"
                + " AND role <> 'SUPER_ADMIN'", Integer.class, unitId("0002.0001"));
        units.forEach(u -> {
            if ("0002.0001".equals(u.get("code").asText())) {
                assertThat(u.get("people").asInt()).isEqualTo(direct);
            }
        });
    }

    @Test
    void adminChiThayNguoiTrongNhanhVaCotChoDeNghi() throws Exception {
        JsonNode m = matrix(admin0002, "q", tag);
        assertThat(m.get("viewer").get("fullList").asBoolean()).isFalse();
        assertThat(m.get("viewer").get("scopeUnitName").asText()).isEqualTo("Phòng Tham mưu");
        assertThat(rowIds(m)).containsExactly(pTop, pChild).doesNotContain(pOther);
        assertThat(mode(m, c1)).as("Admin không phải giám khảo").isEqualTo("REQUEST");
        assertThat(mode(m, c2)).isEqualTo("REQUEST");
        assertThat(rowIds(matrix(admin0003, "q", tag))).containsExactly(pOther);
        // Lọc theo đơn vị ngoài nhánh: 404 như không tồn tại.
        assertThat(call(get("/api/v1/assignments/matrix").param("periodId", String.valueOf(periodId))
                .param("unitId", String.valueOf(unitId("0003"))), admin0002, null).status()).isEqualTo(404);
        JsonNode units = ok(get("/api/v1/assignments/units").param("periodId", String.valueOf(periodId)), admin0002, null, 200).body();
        List<String> codes = new ArrayList<>();
        units.forEach(u -> codes.add(u.get("code").asText()));
        assertThat(codes).contains("0002", "0002.0001").noneMatch(c -> c.startsWith("0003"));
    }

    @Test
    void giamKhaoThayToanBoDanhSachNhungChiCotCuaMinh() throws Exception {
        JsonNode m = matrix(teacher, "q", tag);
        assertThat(m.get("viewer").get("fullList").asBoolean()).isTrue();
        assertThat(rowIds(m)).containsExactly(pTop, pChild, pOther);
        assertThat(m.get("columns").size()).isEqualTo(1);
        assertThat(mode(m, c1)).isEqualTo("DIRECT");
        assertThat(mode(m, c2)).as("không phải nội dung của giám khảo").isNull();
        JsonNode units = ok(get("/api/v1/assignments/units").param("periodId", String.valueOf(periodId)), teacher, null, 200).body();
        assertThat(units.toString()).contains("\"0003\"");
    }

    @Test
    void nguoiDungThuongKhongPhaiGiamKhaoKhongDungDuocBang() throws Exception {
        assertThat(call(get("/api/v1/assignments/matrix").param("periodId", String.valueOf(periodId)), plain, null).status())
                .isEqualTo(404);
        assertThat(call(get("/api/v1/assignments/units").param("periodId", String.valueOf(periodId)), plain, null).status())
                .isEqualTo(404);
        JsonNode res = apply(plain, c1, pChild, "ADD");
        assertThat(res.get("failed").asInt()).isEqualTo(1);
        assertThat(res.get("results").get(0).get("status").asText()).isEqualTo("ERROR");
        assertThat(cell(matrix(sa, "q", tag), pChild, c1)).isNull();
        assertThat(call(get("/api/v1/assignments/matrix").param("periodId", "999999"), sa, null).status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ bộ lọc

    @Test
    void locTheoDonViChuyenNganhVaTimTenHoacMa() throws Exception {
        assertThat(rowIds(matrix(sa, "q", tag, "unitId", String.valueOf(unitId("0002"))))).as("gồm đơn vị con")
                .containsExactly(pTop, pChild);
        assertThat(rowIds(matrix(sa, "q", tag, "unitId", String.valueOf(unitId("0002.0001"))))).containsExactly(pChild);
        assertThat(rowIds(matrix(sa, "q", tag, "unitId", String.valueOf(unitId("0003"))))).containsExactly(pOther);
        assertThat(rowIds(matrix(sa, "q", tag, "unitId", String.valueOf(unitId("0002.0001")), "unitId", String.valueOf(unitId("0003")))))
                .as("nhiều đơn vị cùng lúc").containsExactly(pChild, pOther);

        long specialty = jdbc.queryForObject("SELECT id FROM specialty ORDER BY id LIMIT 1", Long.class);
        String specialtyName = jdbc.queryForObject("SELECT name FROM specialty WHERE id = ?", String.class, specialty);
        tx().executeWithoutResult(s -> jdbc.update("INSERT INTO user_specialty(user_id, specialty_id) VALUES (?, ?)", pChild, specialty));
        JsonNode bySpecialty = matrix(sa, "q", tag, "specialtyId", String.valueOf(specialty));
        assertThat(rowIds(bySpecialty)).containsExactly(pChild);
        assertThat(row(bySpecialty, pChild).get("specialties").toString()).contains(specialtyName);
        long specialty2 = jdbc.queryForObject("SELECT id FROM specialty WHERE id <> ? ORDER BY id LIMIT 1", Long.class, specialty);
        tx().executeWithoutResult(st -> jdbc.update("INSERT INTO user_specialty(user_id, specialty_id) VALUES (?, ?)", pTop, specialty2));
        assertThat(rowIds(matrix(sa, "q", tag, "specialtyId", String.valueOf(specialty), "specialtyId", String.valueOf(specialty2))))
                .as("nhiều chuyên ngành cùng lúc").containsExactly(pTop, pChild);
        assertThat(call(get("/api/v1/assignments/matrix").param("periodId", String.valueOf(periodId))
                .param("unitId", String.valueOf(unitId("0002"))).param("unitId", String.valueOf(unitId("0003"))), admin0002, null).status())
                .as("một đơn vị ngoài nhánh trong danh sách cũng 404").isEqualTo(404);

        String code = jdbc.queryForObject("SELECT personal_code FROM app_user WHERE id = ?", String.class, pOther);
        assertThat(rowIds(matrix(sa, "q", code))).as("tìm theo mã").containsExactly(pOther);
        assertThat(rowIds(matrix(sa, "q", "tran van " + tag.toLowerCase() + "b"))).as("tìm tên không dấu").containsExactly(pChild);
        assertThat(rowIds(matrix(admin0002, "q", code))).as("Admin không tìm ra người ngoài nhánh").isEmpty();
    }

    @Test
    void phanTrangVaTongSo() throws Exception {
        JsonNode first = matrix(sa, "q", tag, "size", "2", "page", "0");
        assertThat(first.get("total").asLong()).isEqualTo(3);
        assertThat(rowIds(first)).containsExactly(pTop, pChild);
        assertThat(rowIds(matrix(sa, "q", tag, "size", "2", "page", "1"))).containsExactly(pOther);
        assertThat(call(get("/api/v1/assignments/matrix").param("periodId", String.valueOf(periodId)).param("size", "500"), sa, null)
                .status()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ thêm, gỡ trực tiếp

    @Test
    void giamKhaoThemGoTrucTiepVaGioiHanTheoNoiDung() throws Exception {
        JsonNode added = apply(teacher, c1, pOther, "ADD");
        assertThat(added.get("applied").asInt()).isEqualTo(1);
        assertThat(cell(matrix(teacher, "q", tag), pOther, c1)).isEqualTo("IN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM class_participant WHERE class_id = ? AND user_id = ? AND removed_at IS NULL",
                Integer.class, c1, pOther)).isEqualTo(1);
        // Thêm trùng: lỗi riêng từng thay đổi.
        JsonNode dup = apply(teacher, c1, pOther, "ADD");
        assertThat(dup.get("results").get(0).get("status").asText()).isEqualTo("ERROR");
        assertThat(dup.get("results").get(0).get("message").asText()).contains("đã có");
        // Nội dung không phải của mình: không thêm được.
        JsonNode other = apply(teacher, c2, pOther, "ADD");
        assertThat(other.get("failed").asInt()).isEqualTo(1);
        assertThat(cell(matrix(sa, "q", tag), pOther, c2)).isNull();

        JsonNode removed = apply(teacher, c1, pOther, "REMOVE");
        assertThat(removed.get("applied").asInt()).isEqualTo(1);
        assertThat(cell(matrix(teacher, "q", tag), pOther, c1)).isNull();
    }

    @Test
    void nguoiDaCoDiemKhongGoDuocBangBangPhanCong() throws Exception {
        long pid = addParticipant(sa, c1, pChild);
        ok(post("/api/v1/classes/" + c1 + "/participants/" + pid + "/result"), sa, Map.of("score", 7), 201);
        assertThat(cell(matrix(sa, "q", tag), pChild, c1)).isEqualTo("IN_GRADED");
        for (MockHttpSession s : List.of(teacher, sa)) {
            JsonNode res = apply(s, c1, pChild, "REMOVE");
            assertThat(res.get("results").get(0).get("status").asText()).isEqualTo("ERROR");
            assertThat(res.get("results").get(0).get("message").asText()).contains("đã có điểm");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM class_participant WHERE id = ? AND removed_at IS NULL", Integer.class, pid))
                .isEqualTo(1);
    }

    @Test
    void noiDungDaDongChiXemVaKhongNhanThem() throws Exception {
        ok(patch("/api/v1/classes/" + c1 + "/status"), sa,
                Map.of("status", "CLOSED"), 200);
        assertThat(mode(matrix(sa, "q", tag), c1)).as("mặc định không hiện nội dung đã đóng").isNull();
        JsonNode withClosed = matrix(sa, "q", tag, "includeClosed", "true");
        assertThat(mode(withClosed, c1)).isEqualTo("VIEW");
        JsonNode res = apply(sa, c1, pTop, "ADD");
        assertThat(res.get("results").get(0).get("message").asText()).contains("đã đóng");
    }

    // ------------------------------------------------------------------ đề nghị của Admin

    @Test
    void adminGuiDeNghiGopMotThongBaoMoiNoiDung() throws Exception {
        int gvBefore = unread(teacher);
        int saBefore = unread(sa);
        int adminBefore = unread(admin0002);

        JsonNode res = apply(admin0002, c1, pTop, "ADD", c1, pChild, "ADD", c2, pChild, "ADD", c1, pOther, "ADD");
        assertThat(res.get("requested").asInt()).isEqualTo(3);
        assertThat(res.get("applied").asInt()).isZero();
        assertThat(res.get("failed").asInt()).as("người ngoài nhánh bị từ chối").isEqualTo(1);
        assertThat(res.get("results").get(3).get("status").asText()).isEqualTo("ERROR");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM class_participant WHERE user_id IN (?, ?, ?) AND removed_at IS NULL",
                Integer.class, pTop, pChild, pOther)).as("Admin chỉ gửi đề nghị, chưa ai vào nội dung").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM class_request WHERE class_id IN (?, ?) AND status = 'PENDING'",
                Integer.class, c1, c2)).isEqualTo(3);

        assertThat(unread(teacher)).as("giám khảo c1 nhận một thông báo cho 2 đề nghị").isEqualTo(gvBefore + 1);
        assertThat(unread(sa)).as("Super Admin: một thông báo mỗi nội dung").isEqualTo(saBefore + 2);
        assertThat(unread(admin0002)).isEqualTo(adminBefore);

        assertThat(cell(matrix(admin0002, "q", tag), pChild, c1)).isEqualTo("PENDING_ADD");
        assertThat(cell(matrix(teacher, "q", tag), pChild, c1)).as("giám khảo thấy đề nghị của nội dung mình").isEqualTo("PENDING_ADD");
        assertThat(cell(matrix(sa, "q", tag), pChild, c2)).isEqualTo("PENDING_ADD");
        assertThat(cell(matrix(admin0003, "q", tag), pOther, c1)).as("Admin khác không thấy đề nghị của người ta").isNull();

        JsonNode again = apply(admin0002, c1, pChild, "ADD");
        assertThat(again.get("results").get(0).get("message").asText()).contains("giống hệt");

        // Giám khảo duyệt đề nghị tạo từ bảng: người vào nội dung, bảng hiện IN.
        long rid = jdbc.queryForObject("SELECT id FROM class_request WHERE class_id = ? AND user_id = ? AND status = 'PENDING'",
                Long.class, c1, pChild);
        ok(post("/api/v1/requests/" + rid + "/approve"), teacher, Map.of(), 200);
        assertThat(cell(matrix(admin0002, "q", tag), pChild, c1)).isEqualTo("IN");
        JsonNode remove = apply(admin0002, c1, pChild, "REMOVE");
        assertThat(remove.get("requested").asInt()).isEqualTo(1);
        assertThat(cell(matrix(admin0002, "q", tag), pChild, c1)).isEqualTo("PENDING_REMOVE");
    }

    @Test
    void giuNguyenThayDoiHopLeKhiCoThayDoiLoi() throws Exception {
        assertThat(call(post("/api/v1/assignments/apply"), sa, Map.of("changes", List.of())).status()).isEqualTo(400);
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 301; i++) {
            many.add(Map.of("classId", c1, "userId", pTop, "action", "ADD"));
        }
        assertThat(call(post("/api/v1/assignments/apply"), sa, Map.of("changes", many)).status()).isEqualTo(400);

        JsonNode res = apply(sa, c1, pTop, "XOA", c1, pChild, "ADD", 999999L, pTop, "ADD");
        assertThat(res.get("results").get(0).get("status").asText()).isEqualTo("ERROR");
        assertThat(res.get("results").get(1).get("status").asText()).as("thay đổi hợp lệ vẫn được áp dụng").isEqualTo("APPLIED");
        assertThat(res.get("results").get(2).get("status").asText()).isEqualTo("ERROR");
        assertThat(cell(matrix(sa, "q", tag), pChild, c1)).isEqualTo("IN");
    }
}
