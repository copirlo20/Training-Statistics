package vn.trainingstats.common.excel;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import vn.trainingstats.common.excel.ExcelBook.DataRow;

/**
 * Duyệt file "mỗi đơn vị một sheet, cột là các nội dung kiểm tra của đợt" (phân công, chấm điểm cả đợt): đếm dòng,
 * lấy mã định danh của từng dòng (thiếu mã, trùng mã báo lỗi qua {@link RowKeys}) và báo lỗi khi file không có cột
 * nội dung nào của đợt.
 */
public final class ContentRows {

    @FunctionalInterface
    public interface RowHandler {
        void handle(String sheet, DataRow row, String code);
    }

    private ContentRows() {
    }

    /**
     * @param contentIds id các nội dung là cột của file ({@link PersonColumns#contentKey})
     * @param needsCode dòng có bắt buộc mã không; dòng không bắt buộc mà thiếu mã thì bỏ qua, không báo lỗi
     * @param noColumnMessage lỗi khi không dòng nào có cột của {@code contentIds} (file cũ, sai đợt)
     * @param handler gọi cho mỗi dòng có mã hợp lệ
     */
    public static void forEach(Map<String, List<DataRow>> sheets, Collection<Long> contentIds, ImportReport.Builder report,
                               Predicate<DataRow> needsCode, String noColumnMessage, RowHandler handler) {
        RowKeys keys = new RowKeys(PersonColumns.CODE.key());
        boolean anyContentColumn = false;
        for (Map.Entry<String, List<DataRow>> sheet : sheets.entrySet()) {
            report.rows(sheet.getValue().size());
            for (DataRow r : sheet.getValue()) {
                anyContentColumn |= contentIds.stream().anyMatch(id -> r.cells().containsKey(PersonColumns.contentKey(id)));
                String code = keys.next(sheet.getKey(), r, report, needsCode.test(r));
                if (code != null) {
                    handler.handle(sheet.getKey(), r, code);
                }
            }
        }
        if (!anyContentColumn && !report.hasErrors()) {
            report.error(null, 0, "Nội dung kiểm tra", noColumnMessage);
        }
    }
}
