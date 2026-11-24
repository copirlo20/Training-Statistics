package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * R5: quét TỰ ĐỘNG mọi endpoint /api có trong ứng dụng, kể cả endpoint thêm sau này.
 * <ul>
 *   <li>Chưa đăng nhập: mọi endpoint không công khai phải trả 401.</li>
 *   <li>User thường (không phải admin, không phải giáo viên), ở một nhánh khác: mọi endpoint ngoài danh sách
 *       "user được dùng" phải trả 403 hoặc 404.</li>
 *   <li>Admin: endpoint chỉ dành cho Super Admin phải trả 403.</li>
 * </ul>
 * Thêm endpoint mới mà user thường được phép gọi thì phải khai báo vào {@link #USER_ALLOWED}, tức là phải nghĩ về
 * phân quyền của nó, thay vì vô tình mở.
 */
class EndpointAccessScanTest extends WebTestBase {

    private static final Set<String> PUBLIC = Set.of(
            "GET /api/v1/auth/csrf",
            "POST /api/v1/auth/login");

    /** Endpoint user thường được gọi (chỉ trên dữ liệu của chính mình). */
    private static final Set<String> USER_ALLOWED = Set.of(
            "POST /api/v1/auth/logout",
            "GET /api/v1/me",
            "PUT /api/v1/me/password",
            // Lớp mình tham gia và điểm của chính mình (lọc theo người đang đăng nhập). ResultApiTest kiểm.
            "GET /api/v1/me/classes",
            // Danh mục chỉ đọc (tên cấp bậc, chức vụ...), không có dữ liệu cá nhân.
            "GET /api/v1/catalogs",
            // Tên và ngày của đợt kiểm tra, dùng để lọc. Không có dữ liệu cá nhân.
            "GET /api/v1/periods",
            "GET /api/v1/periods/{id}",
            // Tự lọc: chỉ lớp mình tham gia hoặc mình dạy (giáo viên có thể là user thường). ClassApiTest kiểm.
            "GET /api/v1/classes",
            // Tự lọc: chỉ đề nghị mình tạo hoặc của lớp mình dạy. RequestApiTest kiểm.
            "GET /api/v1/requests",
            // Thông báo của chính mình. Đọc thông báo theo id của người khác vẫn phải ra 404 (bộ quét kiểm).
            "GET /api/v1/notifications",
            "GET /api/v1/notifications/unread-count",
            "POST /api/v1/notifications/read-all",
            // Bảng phân công: giám khảo có thể là user thường nên không khóa theo vai trò. AssignmentService tự kiểm
            // (Super Admin, giám khảo của nội dung trong đợt, Admin trong nhánh; người khác 404). AssignmentApiTest kiểm.
            "GET /api/v1/assignments/matrix",
            "GET /api/v1/assignments/units",
            "POST /api/v1/assignments/apply",
            // Phân công bằng Excel: cùng quyền với bảng (kiểm trước khi đọc file). AssignmentExcelApiTest kiểm.
            "GET /api/v1/assignments/template",
            "POST /api/v1/assignments/import");
    // Ghi chú: GET /api/v1/users/{id}, /print, /history user thường gọi được NHƯNG chỉ cho chính mình. Không đưa vào
    // danh sách trên, để bộ quét kiểm rằng gọi với id của người khác (id 1 là Super Admin) phải ra 404.

    private static final Set<String> SUPER_ADMIN_ONLY_PREFIXES = Set.of("/api/v1/audit-logs", "/api/v1/admin");

    /** Endpoint ghi chỉ Super Admin được gọi (cùng đường dẫn có thể có phương thức GET mở cho Admin). */
    private static final Set<String> SUPER_ADMIN_ONLY = Set.of(
            "POST /api/v1/units",
            "PUT /api/v1/units/{id}",
            "PATCH /api/v1/units/{id}/status",
            "POST /api/v1/catalogs/{type}",
            "PUT /api/v1/catalogs/{type}/{id}",
            "PATCH /api/v1/catalogs/{type}/{id}/status",
            "POST /api/v1/periods",
            "PUT /api/v1/periods/{id}",
            "PATCH /api/v1/periods/{id}/status",
            "POST /api/v1/periods/{id}/delete",
            "POST /api/v1/classes",
            "PUT /api/v1/classes/{id}",
            "PATCH /api/v1/classes/{id}/status",
            "POST /api/v1/classes/{id}/delete",
            "PUT /api/v1/classes/{id}/teachers",
            "GET /api/v1/classes/import-template",
            "POST /api/v1/classes/import",
            "GET /api/v1/periods/{id}/results/import-template",
            "POST /api/v1/periods/{id}/results/import",
            "PUT /api/v1/results/{rid}",
            "GET /api/v1/results/{rid}/history");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    private record Endpoint(HttpMethod method, String path) {
        String key() {
            return method.name() + " " + path;
        }
    }

    private List<Endpoint> apiEndpoints() {
        List<Endpoint> result = new ArrayList<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            Set<String> patterns = info.getPatternValues();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                for (RequestMethod m : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    result.add(new Endpoint(HttpMethod.valueOf(m.name()), pattern));
                }
            }
        }
        return result;
    }

    private MockHttpServletRequestBuilder call(Endpoint e) {
        String path = e.path().replaceAll("\\{[^}]+}", "1");
        MockHttpServletRequestBuilder b = request(e.method(), path);
        if (e.method() != HttpMethod.GET) {
            b = b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return b;
    }

    @Test
    void quetDuocDanhSachEndpoint() {
        List<String> keys = apiEndpoints().stream().map(Endpoint::key).toList();
        assertThat(keys).as("bộ quét phải thấy endpoint thật, nếu không thì các test dưới vô nghĩa")
                .contains("GET /api/v1/me", "GET /api/v1/audit-logs", "POST /api/v1/auth/login");
    }

    @Test
    void chuaDangNhapNhan401OMoiEndpointKhongCongKhai() throws Exception {
        for (Endpoint e : apiEndpoints()) {
            if (PUBLIC.contains(e.key())) {
                continue;
            }
            int status = mvc.perform(call(e)).andReturn().getResponse().getStatus();
            assertThat(status).as("%s khi chưa đăng nhập", e.key()).isEqualTo(401);
        }
    }

    @Test
    void userThuongNhanhKhacBiChanOMoiEndpointNgoaiDanhSachChoPhep() throws Exception {
        ensureUser("user-nhanh-khac", "USER", "0011.0001", "MatKhau@1", "ACTIVE");
        MockHttpSession session = login("user-nhanh-khac", "MatKhau@1");
        for (Endpoint e : apiEndpoints()) {
            if (PUBLIC.contains(e.key()) || USER_ALLOWED.contains(e.key())) {
                continue;
            }
            int status = mvc.perform(call(e).session(session)).andReturn().getResponse().getStatus();
            assertThat(status).as("%s với user thường ở nhánh khác", e.key()).isIn(403, 404);
        }
    }

    @Test
    void adminKhongVaoDuocEndpointChiDanhChoSuperAdmin() throws Exception {
        ensureUser("admin-quet", "ADMIN", "0002", "MatKhau@1", "ACTIVE");
        MockHttpSession session = login("admin-quet", "MatKhau@1");
        int checked = 0;
        for (Endpoint e : apiEndpoints()) {
            if (SUPER_ADMIN_ONLY_PREFIXES.stream().noneMatch(e.path()::startsWith) && !SUPER_ADMIN_ONLY.contains(e.key())) {
                continue;
            }
            int status = mvc.perform(call(e).session(session)).andReturn().getResponse().getStatus();
            assertThat(status).as("%s với Admin", e.key()).isEqualTo(403);
            checked++;
        }
        assertThat(checked).isGreaterThanOrEqualTo(SUPER_ADMIN_ONLY.size() + 1);
    }
}
