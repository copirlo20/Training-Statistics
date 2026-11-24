package vn.trainingstats.request;

/** Dữ liệu vào, ra của API đề nghị thêm hoặc gỡ người học. */
public final class RequestDtos {

    private RequestDtos() {
    }

    public record ClassRef(long id, String name, String status) {
    }

    /** Đối tượng được đề nghị: chỉ tên, cấp bậc, đơn vị, mã định danh (giống ứng viên, R20). */
    public record TargetRef(long id, String kind, String fullName, String rankSymbol, String unitName,
                            String personalCode) {
    }

    public record PersonRef(long id, String fullName) {
    }

    /**
     * @param decide duyệt hoặc từ chối được (Super Admin hoặc giáo viên của lớp, đề nghị đang chờ)
     * @param cancel hủy được (chính người đề nghị, đề nghị đang chờ)
     */
    public record Access(boolean decide, boolean cancel) {
    }

    public record RequestView(long id, ClassRef classRef, String type, TargetRef target, PersonRef requestedBy,
                              String requestedUnitName, String status, String createdAt, PersonRef decidedBy,
                              String decidedAt, String decisionNote, Access access) {
    }

    /** {@code type} ADD hoặc REMOVE; đối tượng là {@code userId} (người) hoặc {@code unitId} (đơn vị). */
    public record CreateRequest(String type, Long userId, Long unitId) {
    }

    public record DecisionRequest(String note) {
    }
}
