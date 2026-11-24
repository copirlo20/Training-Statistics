package vn.trainingstats.assignment;

import java.util.List;
import java.util.Map;

/** Dữ liệu vào, ra của bảng phân công người học (người x nội dung kiểm tra của một đợt). */
public final class AssignmentDtos {

    private AssignmentDtos() {
    }

    public record PeriodRef(long id, String name, String status) {
    }

    /**
     * Một cột của bảng.
     *
     * @param mode DIRECT: người gọi thêm, gỡ thẳng (Super Admin, giám khảo của nội dung); REQUEST: Admin gửi đề nghị;
     *             VIEW: chỉ xem (nội dung đã đóng)
     */
    public record Column(long id, String name, String examDate, String location, String status, String mode) {
    }

    /**
     * Một người (hàng). {@code cells} chỉ có các ô khác rỗng, khóa là id nội dung: IN (đang tham gia), IN_GRADED (đã có
     * điểm, chỉ gỡ được trên trang nội dung kiểm tra), PENDING_ADD, PENDING_REMOVE (đề nghị đang chờ duyệt).
     * Chỉ gồm tên, cấp bậc, đơn vị, mã định danh và chuyên ngành: không có ngày sinh, số điện thoại, tài khoản.
     */
    public record Row(long userId, String fullName, String rankSymbol, String personalCode, String category, long unitId,
                      String unitCode, String unitName, String parentUnitName, List<String> specialties,
                      Map<Long, String> cells) {
    }

    /** {@code fullList} true: xem toàn Lữ đoàn (Super Admin, giám khảo). Ngược lại chỉ người thuộc {@code scopeUnitName}. */
    public record Viewer(boolean fullList, String scopeUnitName) {
    }

    /**
     * Tham số của bảng phân công, gán theo tên tham số truy vấn. {@code unitId}: gồm đơn vị con; {@code unit}: chỉ người
     * thuộc đúng đơn vị đó (giao diện xem từng đơn vị); {@code category}: đối tượng (SQ, QNCN, HSQ-CS), rỗng là không lọc.
     */
    public record MatrixQuery(Long periodId, List<Long> unitId, Long unit, List<Long> specialtyId, List<String> category,
                              String q, Boolean includeClosed, Integer page, Integer size) {
    }

    public record Matrix(PeriodRef period, Viewer viewer, List<Column> columns, List<Row> rows, int page, int size,
                         long total) {
    }

    /** {@code people}: số người (đang hoạt động) thuộc đúng đơn vị này, không gồm đơn vị con. */
    public record UnitOption(long id, String code, String name, int level, String parentName, int people) {
    }

    /** {@code action}: ADD hoặc REMOVE. */
    public record Change(Long classId, Long userId, String action) {
    }

    public record ApplyRequest(List<Change> changes) {
    }

    /** {@code status}: APPLIED (đã thêm hoặc gỡ), REQUESTED (đã gửi đề nghị), ERROR. */
    public record ItemResult(Long classId, Long userId, String action, String status, String message) {
    }

    public record ApplyResult(List<ItemResult> results, int applied, int requested, int failed) {
    }
}
