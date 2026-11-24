package vn.trainingstats.participant;

/** Dữ liệu vào, ra của API người học trong lớp. */
public final class ParticipantDtos {

    private ParticipantDtos() {
    }

    /**
     * Người học hiển thị trong lớp. {@code unitName} là đơn vị hiện tại; đơn vị lúc vào lớp nằm ở snapshotUnit.
     * {@code category}: đối tượng (SQ, QNCN, HSQ-CS), null với bản ghi cả đơn vị.
     */
    public record Person(long id, String kind, String fullName, String personalCode, String rankSymbol, String rankName,
                         String unitName, String category) {
    }

    public record UnitRef(long id, String code, String name) {
    }

    public record Absence(long reasonId, String reasonName, String note) {
    }

    /**
     * Điểm của người học. {@code grade} do DB tính (cột sinh tự động), không tính lại ở nơi khác.
     *
     * @param edited đã được Super Admin sửa ít nhất một lần (xem lịch sử qua /results/{id}/history)
     */
    public record ResultRef(long id, int scoreX10, String grade, int version, String gradedAt, String gradedByName,
                            boolean edited) {
    }

    public record Removed(String at, String byName, String reason) {
    }

    /**
     * @param state TESTED (đã có điểm), ABSENT (có lý do không kiểm tra), NOT_TESTED (chưa kiểm tra)
     */
    public record Participant(long id, Person user, UnitRef snapshotUnit, String state, Absence absence,
                              ResultRef result, String addedVia, Long requestId, String createdAt, Removed removed) {
    }

    /**
     * Ứng viên tìm trên toàn Lữ đoàn (Q3b, R20). CHỈ tên, cấp bậc, đơn vị, mã định danh: không thêm trường nào khác
     * (ngày sinh, điện thoại, tài khoản...).
     */
    public record Candidate(long id, String fullName, String rankSymbol, String rankName, String unitName,
                            String personalCode, boolean inClass) {
    }

    public record UnitCandidate(long unitId, String code, String name, String path, boolean inClass) {
    }

    /** Thêm người ({@code userId}) hoặc đơn vị ({@code unitId}), đúng một trong hai. */
    public record AddRequest(Long userId, Long unitId) {
    }

    public record RemoveRequest(String reason) {
    }

    /** {@code reasonId} null để xóa lý do không kiểm tra. */
    public record AbsenceRequest(Long reasonId, String note) {
    }
}
