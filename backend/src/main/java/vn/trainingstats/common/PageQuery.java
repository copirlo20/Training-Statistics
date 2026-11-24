package vn.trainingstats.common;

import java.util.List;

/** Tham số phân trang đã kiểm tra: page từ 0, size từ 1 tới {@link #MAX_SIZE}. */
public record PageQuery(int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public static PageQuery of(Integer page, Integer size) {
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 0) {
            throw ApiException.invalidField("page", "Số trang không được âm.");
        }
        if (s < 1 || s > MAX_SIZE) {
            throw ApiException.invalidField("size", "Số dòng mỗi trang phải từ 1 đến " + MAX_SIZE + ".");
        }
        return new PageQuery(p, s);
    }

    public long offset() {
        return (long) page * size;
    }

    public <T> PageResult<T> result(List<T> items, long total) {
        return new PageResult<>(items, page, size, total);
    }
}
