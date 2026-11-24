package vn.trainingstats.common;

import java.util.List;

/** Mẫu phân trang thống nhất. {@code page} tính từ 0. */
public record PageResult<T>(List<T> items, int page, int size, long total) {
}
