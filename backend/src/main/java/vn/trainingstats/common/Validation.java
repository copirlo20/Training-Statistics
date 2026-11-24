package vn.trainingstats.common;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Gom nhiều lỗi theo từng trường rồi ném một lần, để form hiển thị được tất cả lỗi cùng lúc.
 *
 * <pre>
 *   Validation v = new Validation();
 *   String name = v.required("lastName", req.lastName(), 100, "Nhập họ đệm.");
 *   v.throwIfAny();
 * </pre>
 */
public class Validation {

    private final List<ApiError.FieldError> errors = new ArrayList<>();

    public void add(String field, String message) {
        if (errors.stream().noneMatch(e -> e.field().equals(field))) {
            errors.add(new ApiError.FieldError(field, message));
        }
    }

    /** Các lỗi đã gom, theo thứ tự thêm vào (nhập Excel đổi tên trường thành tên cột). */
    public List<ApiError.FieldError> errors() {
        return List.copyOf(errors);
    }

    public boolean has(String field) {
        return errors.stream().anyMatch(e -> e.field().equals(field));
    }

    /** Chuỗi bắt buộc, đã cắt khoảng trắng. Trả null nếu lỗi. */
    public String required(String field, String value, int maxLength, String emptyMessage) {
        String v = trimToNull(value);
        if (v == null) {
            add(field, emptyMessage);
            return null;
        }
        if (v.length() > maxLength) {
            add(field, "Tối đa " + maxLength + " ký tự.");
            return null;
        }
        return v;
    }

    /** Chuỗi không bắt buộc, đã cắt khoảng trắng, rỗng thành null. */
    public String optional(String field, String value, int maxLength) {
        String v = trimToNull(value);
        if (v != null && v.length() > maxLength) {
            add(field, "Tối đa " + maxLength + " ký tự.");
            return null;
        }
        return v;
    }

    public String pattern(String field, String value, Pattern pattern, String message) {
        if (value != null && !pattern.matcher(value).matches()) {
            add(field, message);
            return null;
        }
        return value;
    }

    /** Ngày đã qua dạng yyyy-MM-dd (ví dụ ngày sinh), không bắt buộc. Không chấp nhận ngày trong tương lai. */
    public String pastDate(String field, String value) {
        String v = trimToNull(value);
        if (v == null) {
            return null;
        }
        try {
            LocalDate d = LocalDate.parse(v);
            if (d.isAfter(LocalDate.now()) || d.getYear() < 1900) {
                add(field, "Ngày không hợp lệ.");
                return null;
            }
            return d.toString();
        } catch (DateTimeParseException e) {
            add(field, "Ngày phải có dạng yyyy-MM-dd.");
            return null;
        }
    }

    /** Ngày dạng yyyy-MM-dd, không bắt buộc, cho phép cả ngày tương lai (ngày kiểm tra, ngày bắt đầu đợt). */
    public String date(String field, String value) {
        String v = trimToNull(value);
        if (v == null) {
            return null;
        }
        try {
            LocalDate d = LocalDate.parse(v);
            if (d.getYear() < 1900 || d.getYear() > 2999) {
                add(field, "Ngày không hợp lệ.");
                return null;
            }
            return d.toString();
        } catch (DateTimeParseException e) {
            add(field, "Ngày phải có dạng yyyy-MM-dd.");
            return null;
        }
    }

    public void throwIfAny() {
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Dữ liệu nhập chưa hợp lệ.",
                    List.copyOf(errors));
        }
    }

    public static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
