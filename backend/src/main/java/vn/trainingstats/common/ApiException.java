package vn.trainingstats.common;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Lỗi nghiệp vụ có mã trạng thái HTTP và mã lỗi máy đọc được. Dùng các hàm tạo tĩnh để giữ quy ước:
 * bản ghi ngoài phạm vi trả 404 (không tiết lộ là có tồn tại), trùng hoặc sai version trả 409.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ApiError.FieldError> fieldErrors;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<ApiError.FieldError> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = fieldErrors;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException invalidField(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message,
                List.of(new ApiError.FieldError(field, message)));
    }

    public static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    /** Dùng cả khi bản ghi không tồn tại lẫn khi nằm ngoài phạm vi của người gọi. */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<ApiError.FieldError> fieldErrors() {
        return fieldErrors;
    }
}
