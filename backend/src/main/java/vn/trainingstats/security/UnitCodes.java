package vn.trainingstats.security;

import java.util.List;
import vn.trainingstats.common.VietnameseText;

/**
 * Quy tắc "cùng nhánh" dựa trên mã đơn vị chứa đường dẫn (0002, 0002.0001...). Luôn so với mã gốc cộng dấu chấm,
 * để 0002 không khớp nhầm 00021.
 */
public final class UnitCodes {

    private UnitCodes() {
    }

    /** {@code code} là chính {@code root} hoặc một đơn vị con, cháu của nó. */
    public static boolean isWithin(String code, String root) {
        if (code == null || root == null) {
            return false;
        }
        return code.equals(root) || code.startsWith(root + ".");
    }

    /** Điều kiện SQL tương đương {@link #isWithin} trên một cột mã đơn vị. */
    public static SqlCondition within(String unitCodeColumn, String root) {
        return new SqlCondition(
                "(" + unitCodeColumn + " = ? OR " + unitCodeColumn + " LIKE ? ESCAPE '\\')",
                List.of(root, VietnameseText.escapeLike(root) + ".%"));
    }
}
