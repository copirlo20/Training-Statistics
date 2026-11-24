package vn.trainingstats.common.excel;

import java.util.HashMap;
import java.util.Map;
import vn.trainingstats.common.Validation;
import vn.trainingstats.common.excel.ExcelBook.DataRow;

/** Mã định danh của từng dòng ở file nhiều sheet: kiểm thiếu mã và mã lặp lại giữa mọi sheet. */
public final class RowKeys {

    private static final String COLUMN = "Mã định danh";

    private final String key;
    private final Map<String, String> firstSeen = new HashMap<>();

    /** @param key khóa cột mã trong {@link DataRow} */
    public RowKeys(String key) {
        this.key = key;
    }

    /**
     * Mã của dòng như người dùng ghi (đã bỏ khoảng trắng; so trùng không phân biệt hoa thường). Null khi bỏ qua dòng:
     * thiếu mã (báo lỗi nếu {@code reportMissing}) hoặc trùng mã một dòng trước đó (luôn báo lỗi).
     */
    public String next(String sheet, DataRow row, ImportReport.Builder report, boolean reportMissing) {
        String code = Validation.trimToNull(row.get(key));
        if (code == null) {
            if (reportMissing) {
                report.error(sheet, row.number(), COLUMN, "Thiếu mã định danh.");
            }
            return null;
        }
        String first = firstSeen.putIfAbsent(code.toUpperCase(), sheet + " dòng " + row.number());
        if (first != null) {
            report.error(sheet, row.number(), COLUMN, "Trùng với " + first + ".");
            return null;
        }
        return code;
    }

    public static void unknown(String sheet, DataRow row, ImportReport.Builder report, String message) {
        report.error(sheet, row.number(), COLUMN, message);
    }
}
