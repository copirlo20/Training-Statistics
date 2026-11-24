package vn.trainingstats.exam;

/**
 * Trạng thái hiệu lực của lớp (yêu cầu ngày 2026-11-20): đợt kiểm tra đã đóng thì mọi lớp của đợt coi như đã đóng,
 * không phụ thuộc trạng thái lưu của lớp. Mở lại đợt thì lớp trở về trạng thái lưu của nó (không ghi đè dữ liệu lớp).
 * Mọi truy vấn trả hoặc lọc theo trạng thái lớp dùng biểu thức này, không đọc thẳng {@code exam_class.status}.
 */
public final class ClassStatusSql {

    private ClassStatusSql() {
    }

    /** Biểu thức SQL trạng thái hiệu lực; cần join {@code exam_period} với bí danh {@code period}. */
    public static String effective(String clazz, String period) {
        return "(CASE WHEN " + period + ".status = 'CLOSED' THEN 'CLOSED' ELSE " + clazz + ".status END)";
    }
}
