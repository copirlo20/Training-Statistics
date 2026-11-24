package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Tiện ích chung cho test M3: tạo đợt, nội dung kiểm tra, người, gán giám khảo qua API thật. */
abstract class M3TestBase extends WebTestBase {

    static final String PW = "MatKhau@1";

    record Res(int status, JsonNode body) {
        long id() {
            return body.get("id").asLong();
        }
    }

    Res call(MockHttpServletRequestBuilder b, MockHttpSession s, Object body) throws Exception {
        b = b.session(s);
        if (body != null) {
            b = b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MvcResult r = mvc.perform(b.with(csrf())).andReturn();
        String text = r.getResponse().getContentAsString();
        return new Res(r.getResponse().getStatus(), text.isEmpty() ? null : json.readTree(text));
    }

    Res ok(MockHttpServletRequestBuilder b, MockHttpSession s, Object body, int expected) throws Exception {
        Res r = call(b, s, body);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(expected);
        return r;
    }

    static String uniq(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    long period(MockHttpSession sa) throws Exception {
        return ok(post("/api/v1/periods"), sa, Map.of("name", uniq("Đợt thử"), "startDate", "2026-11-01",
                "endDate", "2026-11-30"), 201).id();
    }

    long newClass(MockHttpSession sa) throws Exception {
        long period = period(sa);
        return ok(post("/api/v1/classes"), sa, Map.of("periodId", period,
                "name", uniq("Lớp thử"), "examDate", "2026-11-20", "location", "Thao trường 1"), 201).id();
    }

    /** Người không có tài khoản, tạo bằng Super Admin. */
    long person(MockHttpSession sa, String unitCode, String firstName) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("category", "SQ");
        body.put("lastName", "Trần Văn");
        body.put("firstName", firstName);
        body.put("personalCode", uniq("M3"));
        body.put("unitId", unitId(unitCode));
        body.put("birthDate", "1995-05-05");
        body.put("phone", "0912345678");
        return ok(post("/api/v1/users"), sa, body, 201).id();
    }

    void setTeachers(MockHttpSession sa, long classId, Long... userIds) throws Exception {
        ok(put("/api/v1/classes/" + classId + "/teachers"), sa, Map.of("userIds", List.of(userIds)), 200);
    }

    long addParticipant(MockHttpSession s, long classId, long userId) throws Exception {
        return ok(post("/api/v1/classes/" + classId + "/participants"), s, Map.of("userId", userId), 201).id();
    }

    long userIdOf(String username) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
    }

    static List<Long> ids(JsonNode array) {
        List<Long> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get("id").asLong()));
        return out;
    }

    static List<Long> userIds(JsonNode participants) {
        List<Long> out = new ArrayList<>();
        participants.forEach(n -> out.add(n.get("user").get("id").asLong()));
        return out;
    }

    int unread(MockHttpSession s) throws Exception {
        return call(get(
                "/api/v1/notifications/unread-count"), s, null).body().get("count").asInt();
    }
}
