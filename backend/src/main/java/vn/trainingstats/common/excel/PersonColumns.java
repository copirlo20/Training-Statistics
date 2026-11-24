package vn.trainingstats.common.excel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import vn.trainingstats.common.excel.ExcelBook.Column;
import vn.trainingstats.common.excel.ExcelTemplate.Field;

/**
 * Năm cột điền sẵn ở đầu mỗi dòng người của file mỗi đơn vị một sheet (phân công, chấm điểm cả đợt). Người được khớp
 * theo cột mã; các cột còn lại chỉ để đối chiếu.
 */
public final class PersonColumns {

    public static final Column NO = new Column("no", "STT", false);
    public static final Column CODE = new Column("code", "Mã định danh *", true);
    public static final Column NAME = new Column("name", "Họ tên", false);
    public static final Column RANK = new Column("rank", "Cấp bậc", false);
    public static final Column CATEGORY = new Column("category", "Đối tượng", false);
    /** Số cột cố định; cột của từng nội dung kiểm tra bắt đầu từ chỉ số này. */
    public static final int COUNT = 5;

    private PersonColumns() {
    }

    /** Năm cột điền sẵn, bị khóa. Danh sách trả về sửa được để thêm cột nội dung phía sau. */
    public static List<Field> fields(String codeNote, String categoryNote) {
        return new ArrayList<>(List.of(
                Field.readOnly(NO, 6, "Số thứ tự, điền sẵn."),
                Field.readOnly(CODE, 16, codeNote),
                Field.readOnly(NAME, 28, "Điền sẵn, chỉ để đối chiếu."),
                Field.readOnly(RANK, 14, "Điền sẵn, chỉ để đối chiếu."),
                Field.readOnly(CATEGORY, 10, categoryNote)));
    }

    /** Khóa cột của một nội dung kiểm tra trong file. */
    public static String contentKey(long classId) {
        return "c" + classId;
    }

    public static Column contentColumn(long classId, String header) {
        return new Column(contentKey(classId), header, false);
    }

    /** Cột để đọc khi nhập: mã, họ tên rồi một cột cho mỗi nội dung ({@code headers}: tiêu đề theo id nội dung). */
    public static List<Column> readColumns(Map<Long, String> headers) {
        List<Column> out = new ArrayList<>(List.of(CODE, NAME));
        headers.forEach((id, header) -> out.add(contentColumn(id, header)));
        return out;
    }
}
