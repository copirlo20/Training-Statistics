package vn.trainingstats.result;

import java.math.BigDecimal;
import vn.trainingstats.participant.ParticipantDtos.Absence;

/** Dữ liệu vào, ra của API điểm và hồ sơ điểm cá nhân. */
public final class ResultDtos {

    private ResultDtos() {
    }

    /** Nhập điểm lần đầu. {@code score} 0.0–10.0, tối đa một chữ số thập phân. */
    public record GradeRequest(BigDecimal score) {
    }

    /** Super Admin sửa điểm: bắt buộc lý do và phiên bản đang xem. */
    public record EditRequest(BigDecimal score, String reason, Integer version) {
    }

    /** Một lần sửa điểm (result_history). Chỉ có điểm, không có xếp loại: xếp loại chỉ tính ở DB cho điểm hiện tại. */
    public record HistoryEntry(long id, int oldScoreX10, int newScoreX10, String reason, String changedByName,
                               String changedAt) {
    }

    public record ClassInfo(long id, String name, String periodName, String examDate,
                            String status) {
    }

    public record Score(int scoreX10, String grade, String gradedAt, boolean edited) {
    }

    /**
     * Một lần tham gia lớp trong hồ sơ cá nhân. Không có dòng đã bị gỡ khỏi lớp.
     *
     * @param state TESTED, ABSENT, NOT_TESTED (như danh sách người học)
     */
    public record ClassResult(long participantId, ClassInfo classInfo, String state, Absence absence, Score result) {
    }
}
