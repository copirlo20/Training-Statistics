package vn.trainingstats.result;

import java.math.BigDecimal;
import java.util.function.Consumer;
import vn.trainingstats.common.Validation;

/**
 * Điểm nhập vào là số 0.0–10.0 có tối đa một chữ số thập phân, lưu dạng số nguyên ×10 (6.5 lưu 65) để không có sai
 * số làm tròn ở điểm biên xếp loại (R15). Nhận {@link BigDecimal} để giữ đúng chữ số người dùng gõ: số thực kiểu
 * double sẽ biến 6.55 thành 6.549999... và không phát hiện được là quá một chữ số thập phân.
 * Dùng chung cho nhập điểm từng người và nhập Excel (M5.4).
 */
public final class Scores {

    private static final BigDecimal MAX = BigDecimal.TEN;

    private Scores() {
    }

    /** Trả điểm ×10, hoặc null và ghi lỗi vào {@code v} nếu không hợp lệ. */
    public static Integer toX10(BigDecimal score, Validation v, String field) {
        if (score == null) {
            v.add(field, "Nhập điểm.");
            return null;
        }
        if (score.signum() < 0 || score.compareTo(MAX) > 0) {
            v.add(field, "Điểm phải từ 0 đến 10.");
            return null;
        }
        if (score.stripTrailingZeros().scale() > 1) {
            v.add(field, "Điểm chỉ có tối đa một chữ số thập phân (ví dụ 6.5).");
            return null;
        }
        return score.movePointRight(1).intValueExact();
    }

    /** Điểm ×10 thành chữ một chữ số thập phân, ví dụ 65 thành "6.5", 80 thành "8.0". */
    public static String text(int scoreX10) {
        return new BigDecimal(scoreX10).movePointLeft(1).toPlainString();
    }

    /** Điểm ×10 từ ô Excel (nhận cả dấu phẩy thập phân), hoặc null nếu không phải điểm hợp lệ. Không báo lỗi. */
    public static Integer fromCellOrNull(String text) {
        try {
            return toX10(new BigDecimal(text.replace(',', '.')), new Validation(), "score");
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Điểm ×10 từ ô Excel, hoặc null và gọi {@code onError} với từng lỗi. */
    public static Integer fromCell(String text, Consumer<String> onError) {
        BigDecimal value;
        try {
            value = new BigDecimal(text.replace(',', '.'));
        } catch (NumberFormatException e) {
            onError.accept("Điểm phải là số từ 0 đến 10.");
            return null;
        }
        Validation v = new Validation();
        Integer score = toX10(value, v, "score");
        v.errors().forEach(e -> onError.accept(e.message()));
        return score;
    }
}
