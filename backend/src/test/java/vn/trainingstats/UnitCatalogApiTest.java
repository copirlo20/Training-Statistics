package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** M2.1: cây đơn vị và danh mục. */
class UnitCatalogApiTest extends WebTestBase {

    private record Res(int status, JsonNode body) {
    }

    private Res call(MockHttpServletRequestBuilder b, MockHttpSession s, Object body) throws Exception {
        b = b.session(s);
        if (body != null) {
            b = b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MvcResult r = mvc.perform(b.with(csrf())).andReturn();
        String text = r.getResponse().getContentAsString();
        return new Res(r.getResponse().getStatus(), text.isEmpty() ? null : json.readTree(text));
    }

    @Test
    void adminXemDuocCayNhungKhongSuaDuoc() throws Exception {
        ensureUser("m21-admin", "ADMIN", "0003", "MatKhau@1", "ACTIVE");
        MockHttpSession admin = login("m21-admin", "MatKhau@1");
        Res tree = call(get("/api/v1/units/tree"), admin, null);
        assertThat(tree.status()).isEqualTo(200);
        assertThat(tree.body().size()).isGreaterThanOrEqualTo(25);
        assertThat(call(post("/api/v1/units"), admin, Map.of("name", "X")).status()).isEqualTo(403);
        assertThat(call(post("/api/v1/catalogs/ranks"), admin, Map.of("name", "X", "symbol", "X")).status()).isEqualTo(403);
    }

    @Test
    void superAdminTaoDonViCap2MaTuDongVaKhongTaoDuocCap3() throws Exception {
        MockHttpSession sa = login(SA_USERNAME, SA_PASSWORD);
        long parent = unitId("0011");   // Tàu 414, đang có 0011.0001..0011.0005
        Res created = call(post("/api/v1/units"), sa, Map.of("parentId", parent, "name", "Tổ thử nghiệm"));
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        assertThat(created.body().get("code").asText()).isEqualTo("0011.0006");
        assertThat(created.body().get("level").asInt()).isEqualTo(2);

        Res level3 = call(post("/api/v1/units"), sa, Map.of("parentId", created.body().get("id").asLong(), "name", "Cấp 3"));
        assertThat(level3.status()).isEqualTo(400);

        Res manual = call(post("/api/v1/units"), sa, Map.of("parentId", parent, "segment", "0006", "name", "Trùng mã"));
        assertThat(manual.status()).isEqualTo(400);
        assertThat(manual.body().toString()).contains("đã tồn tại");

        long id = created.body().get("id").asLong();
        Res renamed = call(put("/api/v1/units/" + id), sa, Map.of("name", "Tổ thử nghiệm (đổi tên)"));
        assertThat(renamed.body().get("name").asText()).isEqualTo("Tổ thử nghiệm (đổi tên)");
        assertThat(renamed.body().get("code").asText()).isEqualTo("0011.0006");
        Res locked = call(patch("/api/v1/units/" + id + "/status"), sa, Map.of("active", false));
        assertThat(locked.body().get("active").asBoolean()).isFalse();

        // Đơn vị đã khóa không nhận người mới.
        Res user = call(post("/api/v1/users"), sa, Map.of("category", "SQ", "lastName", "A", "firstName", "B",
                "personalCode", "LOCK-" + System.nanoTime(), "unitId", id));
        assertThat(user.status()).isEqualTo(400);
    }

    @Test
    void danhMucThemSuaKhoaVaChanTrung() throws Exception {
        MockHttpSession sa = login(SA_USERNAME, SA_PASSWORD);
        Res specialty = call(post("/api/v1/catalogs/specialties"), sa, Map.of("name", "Bắn pháo tàu"));
        assertThat(specialty.status()).isEqualTo(201);
        long id = specialty.body().get("id").asLong();
        assertThat(call(post("/api/v1/catalogs/specialties"), sa, Map.of("name", "Bắn pháo tàu")).status()).isEqualTo(409);
        assertThat(call(put("/api/v1/catalogs/specialties/" + id), sa, Map.of("name", "Bắn pháo tàu ban ngày")).body()
                .get("name").asText()).isEqualTo("Bắn pháo tàu ban ngày");
        assertThat(call(patch("/api/v1/catalogs/specialties/" + id + "/status"), sa, Map.of("active", false)).body()
                .get("active").asBoolean()).isFalse();
        assertThat(call(post("/api/v1/catalogs/subjects"), sa, Map.of("name", "Môn")).status())
                .as("danh mục môn kiểm tra đã bỏ").isEqualTo(404);

        assertThat(call(post("/api/v1/catalogs/ranks"), sa, Map.of("name", "Cấp bậc thử")).status()).isEqualTo(400);
        assertThat(call(post("/api/v1/catalogs/khong-co"), sa, Map.of("name", "X")).status()).isEqualTo(404);

        Res all = call(get("/api/v1/catalogs"), sa, null);
        assertThat(all.body().get("ranks").size()).isGreaterThanOrEqualTo(20);
        assertThat(all.body().has("absenceReasons")).isTrue();
        assertThat(all.body().get("specialties").toString()).contains("Bắn pháo tàu ban ngày");
        assertThat(all.body().has("subjects")).isFalse();
    }
}
