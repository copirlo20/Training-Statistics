package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Đối chiếu dữ liệu seed trong DB với danh sách gốc cố định dưới đây. Dữ liệu ban đầu lấy từ file Excel
 * yêu cầu của chủ dự án lúc viết V1__init.sql; file đó đã xóa khỏi repo, bảng seed chép thẳng vào test.
 */
class SeedDataTest extends DbTestBase {

    private static final List<String[]> RANKS = List.of(
            new String[] {"Đại tá", "4//"},
            new String[] {"Thượng tá", "3//"},
            new String[] {"Trung tá", "2//"},
            new String[] {"Thiếu tá", "1//"},
            new String[] {"Đại úy", "4/"},
            new String[] {"Thượng úy", "3/"},
            new String[] {"Trung úy", "2/"},
            new String[] {"Thiếu úy", "1/"},
            new String[] {"Thượng tá QNCN", "3//CN"},
            new String[] {"Trung tá QNCN", "2//CN"},
            new String[] {"Thiếu tá QNCN", "1//CN"},
            new String[] {"Đại úy QNCN", "4/CN"},
            new String[] {"Thượng úy QNCN", "3/CN"},
            new String[] {"Trung úy QNCN", "2/CN"},
            new String[] {"Thiếu úy QNCN", "1/CN"},
            new String[] {"Thượng sỹ", "H3"},
            new String[] {"Trung sỹ", "H2"},
            new String[] {"Hạ sỹ", "H1"},
            new String[] {"Binh nhất", "B1"},
            new String[] {"Binh nhì", "B2"});

    private static final List<String> POSITIONS = List.of(
            "Lữ đoàn trưởng", "Chính ủy", "Phó Lữ đoàn trưởng - Tham mưu trưởng", "Phó Lữ đoàn trưởng", "Phó Chính ủy",
            "Phó Tham mưu trưởng", "Chủ nhiệm Chính trị", "Phó Chủ nhiệm Chính trị", "Chủ nhiệm Hậu cần - Kỹ thuật",
            "Phó Chủ nhiệm Hậu cần - Kỹ thuật", "Trưởng ban", "Thuyền trưởng", "Phó Thuyền trưởng", "Hải đội trưởng",
            "Phó Hải đội trưởng", "Chính trị viên", "Chính trị viên phó", "Trưởng ngành", "Phó Trưởng ngành",
            "Tập sự Trưởng ngành", "Nghiệp vụ", "Nghiệp vụ trưởng", "Trợ lý", "Tiểu đội trưởng", "Nhân viên", "Chiến sỹ");

    private static final List<String> SPECIALTIES = List.of(
            "Hàng hải", "Tên lửa", "Pháo tàu", "Ngư lôi", "Thông tin", "Radar", "Sonar", "Không quân", "Hóa học",
            "Máy tàu", "Điện tàu", "Công nghệ thông tin", "Tài chính", "Xăng dầu", "Doanh trại", "Quân nhu", "Xe máy",
            "Quân y", "Tuyên huấn", "Cán bộ", "Tổ chức", "Chính trị", "Tham mưu", "Hậu cần - Kỹ thuật");

    /** Mỗi phần tử: {tên, cấp, tên đơn vị cha hoặc rỗng}, theo đúng thứ tự sort_order trong V1__init.sql. */
    private static final List<String[]> UNITS = List.of(
            new String[] {"Thủ trưởng Lữ đoàn", "1", ""},
            new String[] {"Phòng Tham mưu", "1", ""},
            new String[] {"Ban Quân huấn", "2", "Phòng Tham mưu"},
            new String[] {"Ban Tác chiến", "2", "Phòng Tham mưu"},
            new String[] {"Ban Hành chính", "2", "Phòng Tham mưu"},
            new String[] {"Trung đội Thông tin", "2", "Phòng Tham mưu"},
            new String[] {"Cảng vụ", "2", "Phòng Tham mưu"},
            new String[] {"Phòng Chính trị", "1", ""},
            new String[] {"Phòng Hậu cần - Kỹ thuật", "1", ""},
            new String[] {"Tàu 011", "1", ""},
            new String[] {"Tàu 012", "1", ""},
            new String[] {"Tàu 015", "1", ""},
            new String[] {"Tàu 016", "1", ""},
            new String[] {"Tàu 26", "1", ""},
            new String[] {"Hải đội 412", "1", ""},
            new String[] {"Tàu 375", "2", "Hải đội 412"},
            new String[] {"Tàu 376", "2", "Hải đội 412"},
            new String[] {"Tàu 381", "2", "Hải đội 412"},
            new String[] {"Hải đội bộ", "2", "Hải đội 412"},
            new String[] {"Hải đội 414", "1", ""},
            new String[] {"Tàu 371", "2", "Hải đội 414"},
            new String[] {"Tàu 372", "2", "Hải đội 414"},
            new String[] {"Tàu 373", "2", "Hải đội 414"},
            new String[] {"Tàu 374", "2", "Hải đội 414"},
            new String[] {"Hải đội bộ", "2", "Hải đội 414"});

    @Test
    void capBacKhopDuLieuGoc() {
        List<Map<String, Object>> db = jdbc.queryForList("SELECT name, symbol FROM rank ORDER BY sort_order");
        assertThat(db).hasSameSizeAs(RANKS);
        for (int i = 0; i < RANKS.size(); i++) {
            assertThat(db.get(i).get("name")).as("tên cấp bậc #%d", i + 1).isEqualTo(RANKS.get(i)[0]);
            assertThat(db.get(i).get("symbol")).as("ký hiệu cấp bậc #%d", i + 1).isEqualTo(RANKS.get(i)[1]);
        }
    }

    @Test
    void chucVuKhopDuLieuGoc() {
        assertThat(jdbc.queryForList("SELECT name FROM position ORDER BY sort_order", String.class))
                .containsExactlyElementsOf(POSITIONS);
    }

    @Test
    void chuyenNganhKhopDuLieuGoc() {
        assertThat(jdbc.queryForList("SELECT name FROM specialty ORDER BY sort_order", String.class))
                .containsExactlyElementsOf(SPECIALTIES);
    }

    @Test
    void lyDoKhongKiemTraLaBonGiaTriDeXuat() {
        assertThat(jdbc.queryForList("SELECT name FROM absence_reason ORDER BY sort_order", String.class))
                .containsExactly("Ốm", "Công tác", "Nghỉ phép", "Khác");
    }

    @Test
    void cayDonViKhopDuLieuGocVaMaDungQuyTac() {
        List<Map<String, Object>> db = jdbc.queryForList(
                "SELECT u.name AS name, u.level AS level, COALESCE(p.name, '') AS parent, u.code AS code, p.code AS parent_code "
                        + "FROM unit u LEFT JOIN unit p ON p.id = u.parent_id ORDER BY u.sort_order");
        assertThat(db).hasSameSizeAs(UNITS);
        for (int i = 0; i < UNITS.size(); i++) {
            String[] expected = UNITS.get(i);
            Map<String, Object> actual = db.get(i);
            assertThat(actual.get("name")).as("tên đơn vị #%d", i + 1).isEqualTo(expected[0]);
            assertThat(String.valueOf(actual.get("level"))).as("cấp của %s", expected[0]).isEqualTo(expected[1]);
            assertThat(actual.get("parent")).as("đơn vị cha của %s", expected[0]).isEqualTo(expected[2]);
            String code = (String) actual.get("code");
            if (expected[1].equals("1")) {
                assertThat(code).as("mã cấp 1 của %s", expected[0]).matches("\\d{4}");
            } else {
                assertThat(code).as("mã cấp 2 của %s", expected[0]).matches(actual.get("parent_code") + "\\.\\d{4}");
            }
        }
    }

    @Test
    void khongCoDonViCap0VaKhongCoNguoiDungSeed() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM unit WHERE level NOT IN (1, 2)", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class))
                .as("Super Admin được tạo ở lần chạy đầu (M1.6), không seed bằng SQL")
                .isZero();
    }

    @Test
    void haiHaiDoiBoCungTenNhungKhacMa() {
        List<String> codes = jdbc.queryForList("SELECT code FROM unit WHERE name = 'Hải đội bộ' ORDER BY code", String.class);
        assertThat(codes).hasSize(2);
        assertThat(codes.get(0)).isNotEqualTo(codes.get(1));
    }
}
