package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Kiểm tra qua HTTP thật (không qua MockMvc), vì thuộc tính cookie phiên do Tomcat đặt và chỉ thấy được trên
 * response thật: header bảo mật, cookie phiên HttpOnly + SameSite=Lax + không Secure, cookie CSRF đọc được bằng JS.
 */
// Context riêng: csrf() của spring-security-test thay kho token CSRF trong chuỗi filter thật bằng kho dựa trên session.
// Các test MockMvc dùng chung context sẽ làm server thật ở đây không còn đặt cookie XSRF-TOKEN.
@TestPropertySource(properties = "test.context=http-that")
class HttpHeadersTest extends WebTestBase {

    private final HttpClient client = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void headerBaoMatTrenTrangGiaoDien() throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(uri("/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(v -> {
            assertThat(v).contains("default-src 'self'").contains("frame-ancestors 'none'").contains("object-src 'none'");
        });
        assertThat(r.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(r.headers().firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(r.headers().firstValue("Referrer-Policy")).hasValue("same-origin");
    }

    @Test
    void duongDanKhongDuocMoBiChanTheoMacDinh() throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(uri("/bi-mat.txt")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isIn(401, 403);
    }

    @Test
    void cookiePhienVaCookieCsrfDungThuocTinh() throws Exception {
        HttpResponse<String> csrf = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String xsrfCookie = cookie(csrf.headers().allValues("Set-Cookie"), "XSRF-TOKEN").orElseThrow(
                () -> new AssertionError("Không có cookie XSRF-TOKEN. Status " + csrf.statusCode() + ", headers " + csrf.headers().map()));
        assertThat(xsrfCookie).as("JS phải đọc được cookie CSRF").doesNotContainIgnoringCase("HttpOnly");
        assertThat(xsrfCookie).containsIgnoringCase("SameSite=Lax").doesNotContainIgnoringCase("Secure");
        String token = xsrfCookie.substring("XSRF-TOKEN=".length(), xsrfCookie.indexOf(';'));
        assertThat(csrf.body()).contains("\"headerName\":\"X-XSRF-TOKEN\"").contains(token);

        String body = "{\"username\":\"" + SA_USERNAME + "\",\"password\":\"" + SA_PASSWORD + "\"}";
        HttpResponse<String> login = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                        .header("Content-Type", "application/json")
                        .header("X-XSRF-TOKEN", token)
                        .header("Cookie", "XSRF-TOKEN=" + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String session = cookie(login.headers().allValues("Set-Cookie"), "TSSESSION").orElseThrow();
        assertThat(session).containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax");
        assertThat(session).as("chạy HTTP nên không đặt Secure").doesNotContainIgnoringCase("Secure");
        assertThat(login.body()).doesNotContain("password").doesNotContain("$2");
    }

    private static Optional<String> cookie(List<String> setCookies, String name) {
        return setCookies.stream().filter(c -> c.startsWith(name + "=") && !c.startsWith(name + "=;")).findFirst();
    }
}
