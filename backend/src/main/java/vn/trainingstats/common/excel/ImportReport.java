package vn.trainingstats.common.excel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kết quả một lần nhập Excel, cùng dạng cho người dùng, lớp và điểm.
 *
 * @param dryRun     chỉ kiểm tra, không ghi
 * @param written    đã ghi vào DB (chỉ khi không dryRun và không có lỗi nào: ghi tất cả hoặc không ghi gì)
 * @param totalRows  số dòng dữ liệu đọc được (mọi sheet)
 * @param errors     lỗi theo dòng và cột; có lỗi thì không ghi gì
 * @param rows       xem trước việc sẽ làm với từng dòng hợp lệ (R8: hiện họ tên người được khớp để đối chiếu)
 * @param counts     số lượng theo loại việc, ví dụ {CREATE: 10, UPDATE: 3}
 */
public record ImportReport(boolean dryRun, boolean written, int totalRows, int errorCount, List<Error> errors,
                           List<Item> rows, Map<String, Integer> counts) {

    /** {@code sheet} null khi file chỉ có một sheet dữ liệu. */
    public record Error(String sheet, int row, String column, String message) {
    }

    /**
     * @param action việc sẽ làm: CREATE, UPDATE, ADD (thêm người vào lớp), GRADE, SKIP
     * @param label  đối tượng (họ tên, tên lớp)
     * @param detail thông tin để đối chiếu (người được khớp, đơn vị, điểm...)
     */
    public record Item(String sheet, int row, String action, String label, String detail) {
    }

    /** Gom lỗi và dòng xem trước trong lúc kiểm tra. */
    public static final class Builder {
        private final List<Error> errors = new ArrayList<>();
        private final List<Item> items = new ArrayList<>();
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        private int totalRows;

        public void rows(int n) {
            totalRows += n;
        }

        public void error(String sheet, int row, String column, String message) {
            errors.add(new Error(sheet, row, column, message));
        }

        public void item(String sheet, int row, String action, String label, String detail) {
            items.add(new Item(sheet, row, action, label, detail));
            counts.merge(action, 1, Integer::sum);
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public int errorCount() {
            return errors.size();
        }

        public ImportReport build(boolean dryRun, boolean written) {
            return new ImportReport(dryRun, written, totalRows, errors.size(), List.copyOf(errors), List.copyOf(items),
                    Collections.unmodifiableMap(new LinkedHashMap<>(counts)));
        }
    }
}
