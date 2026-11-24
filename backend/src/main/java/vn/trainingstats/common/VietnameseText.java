package vn.trainingstats.common;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Chuẩn hóa tiếng Việt cho tìm kiếm (ARCHITECTURE R6). LIKE của SQLite chỉ không phân biệt hoa thường với ký tự
 * ASCII, nên lưu thêm cột search_text đã bỏ dấu, chữ thường, rồi chuẩn hóa chuỗi người dùng gõ theo cùng cách.
 */
public final class VietnameseText {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private VietnameseText() {
    }

    /** "Nguyễn Trọng  Đạt" thành "nguyen trong dat". Chuỗi null thành chuỗi rỗng. */
    public static String searchKey(String text) {
        if (text == null) {
            return "";
        }
        // đ/Đ không tách được thành d + dấu khi chuẩn hóa NFD, phải đổi tay.
        String s = text.replace('đ', 'd').replace('Đ', 'D');
        s = Normalizer.normalize(s, Normalizer.Form.NFD);
        s = MARKS.matcher(s).replaceAll("");
        s = SPACES.matcher(s).replaceAll(" ").trim();
        return s.toLowerCase(Locale.ROOT);
    }

    /** Ghép các phần (họ, tên, mã...) thành một khóa tìm kiếm, bỏ qua phần rỗng. */
    public static String searchKey(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String key = searchKey(part);
            if (!key.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(key);
            }
        }
        return sb.toString();
    }

    /**
     * Mẫu cho {@code LIKE ? ESCAPE '\'} tìm "chứa chuỗi": chuẩn hóa như searchKey rồi thoát ký tự đặc biệt của LIKE,
     * để người dùng gõ "%" hay "_" không thành ký tự đại diện.
     */
    public static String likeContains(String userInput) {
        return "%" + escapeLike(searchKey(userInput)) + "%";
    }

    public static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
