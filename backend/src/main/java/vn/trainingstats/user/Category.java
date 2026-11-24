package vn.trainingstats.user;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vn.trainingstats.common.VietnameseText;

/**
 * Đối tượng của một người (thêm ngày 2026-11-20): SQ sĩ quan, QNCN quân nhân chuyên nghiệp, HSQ-CS hạ sĩ quan - chiến
 * sĩ. Mã lưu trong cột {@code app_user.category}; bắt buộc với mọi người có đơn vị (máy chủ kiểm khi tạo, sửa, nhập).
 */
public final class Category {

    public static final Map<String, String> LABELS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("SQ", "Sĩ quan");
        m.put("QNCN", "Quân nhân chuyên nghiệp");
        m.put("HSQ-CS", "Hạ sĩ quan - chiến sĩ");
        LABELS = Collections.unmodifiableMap(m);
    }

    public static final List<String> CODES = List.copyOf(LABELS.keySet());

    private Category() {
    }

    /**
     * Mã đối tượng từ chữ người dùng gõ: chấp nhận mã (SQ, QNCN, HSQ-CS, HSQ CS, HSQCS) hoặc tên đầy đủ, không phân
     * biệt hoa thường, dấu. Null nếu không nhận ra.
     */
    public static String parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = VietnameseText.searchKey(raw).replaceAll("[^a-z0-9]", "");
        for (Map.Entry<String, String> e : LABELS.entrySet()) {
            if (key.equals(e.getKey().toLowerCase().replace("-", ""))
                    || key.equals(VietnameseText.searchKey(e.getValue()).replaceAll("[^a-z0-9]", ""))) {
                return e.getKey();
            }
        }
        return null;
    }
}
