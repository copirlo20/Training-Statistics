package vn.trainingstats.exam;

import java.util.List;

/** Dữ liệu vào, ra của API lớp kiểm tra. */
public final class ClassDtos {

    private ClassDtos() {
    }

    public record Ref(long id, String name) {
    }

    public record PeriodRef(long id, String name, String status) {
    }

    /** Một người được hiển thị ngắn gọn: tên, cấp bậc, đơn vị (không có dữ liệu cá nhân khác). */
    public record PersonRef(long id, String fullName, String rankSymbol, String unitName) {
    }

    /**
     * @param participantCount số người học (chưa bị gỡ) mà người gọi được thấy: cả lớp với Super Admin và giáo viên,
     *                         người trong nhánh với Admin, chính mình với người dùng thường
     * @param gradedCount      trong số đó, số người đã có điểm
     * @param pendingCount     trong số đó, số người còn phải chấm (chưa có điểm, chưa ghi lý do vắng)
     * @param teaching         người gọi là giáo viên của lớp
     */
    public record ClassListItem(long id, String name, PeriodRef period, String examDate, String location,
                                String status, int participantCount, int gradedCount, int pendingCount,
                                boolean teaching) {
    }

    /** Lớp đang mở, để Admin chọn khi tạo đề nghị. Chỉ thông tin chung. */
    public record OpenClass(long id, String name, String periodName, String examDate) {
    }

    /**
     * @param manage  thêm, gỡ người học, ghi lý do vắng, duyệt đề nghị (Super Admin hoặc giáo viên của lớp)
     * @param request tạo đề nghị (Admin, lớp đang mở)
     * @param edit    sửa lớp, gán giáo viên, đóng, mở, xóa (Super Admin)
     */
    public record Access(boolean manage, boolean request, boolean edit) {
    }

    public record ClassView(long id, String name, PeriodRef period, String examDate, String location,
                            String note, String status, int version, List<PersonRef> teachers, int participantCount,
                            Access access) {
    }

    /** {@code teacherIds} null: giữ nguyên danh sách giám khảo; có giá trị (kể cả rỗng): thay toàn bộ, cùng giao dịch. */
    public record SaveRequest(Long periodId, String name, String examDate, String location, String note,
                              Integer version, List<Long> teacherIds) {
    }

    public record StatusRequest(String status) {
    }

    public record TeachersRequest(List<Long> userIds) {
    }
}
