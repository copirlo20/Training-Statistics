package vn.trainingstats.common;

/**
 * Khóa lạc quan: câu UPDATE luôn kèm {@code WHERE id = ? AND version = ?} và tăng version.
 * Không dòng nào bị cập nhật nghĩa là người khác đã sửa trước (hoặc bản ghi không còn), trả 409.
 */
public final class OptimisticLock {

    private OptimisticLock() {
    }

    public static void check(int updatedRows) {
        if (updatedRows == 0) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "Dữ liệu đã được người khác thay đổi. Vui lòng tải lại rồi thử lại.");
        }
    }
}
