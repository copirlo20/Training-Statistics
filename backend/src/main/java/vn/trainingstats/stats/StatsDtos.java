package vn.trainingstats.stats;

import java.util.List;

/** Dữ liệu trả về của API thống kê. Mọi con số đã giới hạn theo phạm vi người xem và theo một loại (PERSON hoặc UNIT). */
public final class StatsDtos {

    private StatsDtos() {
    }

    /**
     * Số liệu của một nhóm người học (chưa bị gỡ khỏi lớp).
     *
     * @param total     số lượt người học
     * @param tested    đã có điểm
     * @param absent    không kiểm tra (có lý do vắng)
     * @param notTested chưa có điểm, chưa ghi lý do
     * @param fail      số điểm theo từng xếp loại (lấy từ cột {@code result.grade} của DB)
     * @param avgX10    điểm trung bình ×10 (chỉ để hiển thị, không lưu), null nếu chưa có điểm
     */
    public record Counts(long total, long tested, long absent, long notTested, long fail, long pass, long good,
                         long excellent, Double avgX10, Integer maxX10, Integer minX10) {
    }

    public record UnitRow(long unitId, String code, String name, Counts counts) {
    }

    /** @param group {@code unit}: theo đơn vị lúc vào lớp; {@code top}: gộp về đơn vị cấp 1 */
    public record ByUnit(String kind, String group, List<UnitRow> rows, Counts totals) {
    }

    public record PeriodRow(long periodId, String name, String startDate, Counts counts) {
    }

    public record ContentRow(long classId, String name, String examDate, Counts counts) {
    }

    /** Dashboard: tổng, theo đơn vị, theo đợt (gần nhất trước), theo nội dung kiểm tra (ngày kiểm tra gần nhất trước). */
    public record Overview(String kind, Counts totals, List<UnitRow> byUnit, List<PeriodRow> byPeriod,
                           List<ContentRow> byContent) {
    }

    /** Một khoảng điểm của biểu đồ phân bố: [fromX10, toX10), khoảng cuối gồm cả 10. */
    public record Bucket(int fromX10, int toX10, long count) {
    }

    public record ClassInfo(long id, String name, String periodName, String examDate, String status) {
    }

    public record ClassStats(ClassInfo classInfo, String kind, Counts counts, List<Bucket> histogram,
                             List<UnitRow> byUnit) {
    }

    /**
     * Một người chưa có điểm.
     *
     * @param state ABSENT (có lý do không kiểm tra) hoặc NOT_TESTED
     */
    public record NotTestedItem(long participantId, long userId, String fullName, String rankSymbol, String personalCode,
                                String unitName, long classId, String className, String periodName,
                                String examDate, String state, String reasonName, String note) {
    }

    public record ReasonCount(String reason, long count) {
    }

    public record NotTestedPage(List<NotTestedItem> items, int page, int size, long total, long absent, long notTested,
                                List<ReasonCount> reasons) {
    }
}
