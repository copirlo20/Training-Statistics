package vn.trainingstats.common;

import java.util.List;

/** Định dạng lỗi thống nhất của mọi API. {@code message} là câu tiếng Việt hiển thị được cho người dùng. */
public record ApiError(String code, String message, List<FieldError> fieldErrors) {

    public ApiError(String code, String message) {
        this(code, message, List.of());
    }

    public record FieldError(String field, String message) {
    }
}
