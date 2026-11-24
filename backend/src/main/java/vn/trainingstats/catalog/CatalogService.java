package vn.trainingstats.catalog;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.trainingstats.audit.AuditAction;
import vn.trainingstats.audit.AuditService;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.Validation;
import vn.trainingstats.common.VietnameseText;

/** Danh mục: cấp bậc, chức vụ, chuyên ngành, lý do không kiểm tra. Không xóa, chỉ khóa (active = 0). */
@Service
public class CatalogService {

    /** Loại danh mục. Tên bảng chỉ lấy từ đây (danh sách trắng), không bao giờ từ dữ liệu người dùng. */
    public enum Type {
        RANKS("ranks", "rank", "ranks", true),
        POSITIONS("positions", "position", "positions", false),
        SPECIALTIES("specialties", "specialty", "specialties", false),
        ABSENCE_REASONS("absence-reasons", "absence_reason", "absenceReasons", false);

        final String path;
        final String table;
        final String jsonKey;
        final boolean hasSymbol;

        Type(String path, String table, String jsonKey, boolean hasSymbol) {
            this.path = path;
            this.table = table;
            this.jsonKey = jsonKey;
            this.hasSymbol = hasSymbol;
        }

        public static Type fromPath(String path) {
            return Arrays.stream(values()).filter(t -> t.path.equals(path)).findFirst()
                    .orElseThrow(() -> ApiException.notFound("Không có danh mục này."));
        }
    }

    public record Item(long id, String name, String symbol, int sortOrder, boolean active) {
    }

    public record SaveRequest(String name, String symbol, Integer sortOrder) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public CatalogService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static RowMapper<Item> mapper(Type t) {
        return (rs, i) -> new Item(rs.getLong("id"), rs.getString("name"), t.hasSymbol ? rs.getString("symbol") : null,
                rs.getInt("sort_order"), rs.getInt("active") == 1);
    }

    private static String columns(Type t) {
        return t.hasSymbol ? "id, name, symbol, sort_order, active" : "id, name, sort_order, active";
    }

    /** Mọi danh mục, kể cả mục đã khóa (để vẫn hiển thị đúng dữ liệu cũ). */
    public Map<String, List<Item>> all() {
        Map<String, List<Item>> out = new LinkedHashMap<>();
        for (Type t : Type.values()) {
            out.put(t.jsonKey, list(t));
        }
        return out;
    }

    public List<Item> list(Type t) {
        return jdbc.query("SELECT " + columns(t) + " FROM " + t.table + " ORDER BY sort_order, name", mapper(t));
    }

    /**
     * Danh mục theo tên đã bỏ dấu, chữ thường (nhập Excel tìm theo tên: "Thượng úy" và "thuong uy" đều khớp).
     * Gồm cả mục đã khóa, để báo lỗi "đã bị khóa" thay vì "không có".
     */
    public Map<String, Item> byName(Type t) {
        Map<String, Item> out = new LinkedHashMap<>();
        for (Item i : list(t)) {
            out.putIfAbsent(VietnameseText.searchKey(i.name()), i);
        }
        return out;
    }

    public Item require(Type t, long id) {
        return jdbc.query("SELECT " + columns(t) + " FROM " + t.table + " WHERE id = ?", mapper(t), id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Không tìm thấy mục danh mục."));
    }

    /** Mục tồn tại và đang dùng được, để gán cho người dùng. */
    public boolean isActive(Type t, long id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + t.table + " WHERE id = ? AND active = 1",
                Integer.class, id);
        return n != null && n > 0;
    }

    /** Tên và ký hiệu (chỉ cấp bậc có ký hiệu) đã kiểm. */
    private record Fields(String name, String symbol) {
    }

    private static Fields validate(Type t, SaveRequest req) {
        Validation v = new Validation();
        String name = v.required("name", req.name(), 100, "Nhập tên.");
        String symbol = t.hasSymbol ? v.required("symbol", req.symbol(), 20, "Nhập ký hiệu.") : null;
        v.throwIfAny();
        return new Fields(name, symbol);
    }

    @Transactional
    public Item create(Type t, SaveRequest req) {
        Fields f = validate(t, req);
        String name = f.name();
        String symbol = f.symbol();
        Integer max = jdbc.queryForObject("SELECT MAX(sort_order) FROM " + t.table, Integer.class);
        int sortOrder = req.sortOrder() != null ? req.sortOrder() : (max == null ? 0 : max) + 1;
        try {
            if (t.hasSymbol) {
                jdbc.update("INSERT INTO " + t.table + " (name, symbol, sort_order) VALUES (?, ?, ?)", name, symbol, sortOrder);
            } else {
                jdbc.update("INSERT INTO " + t.table + " (name, sort_order) VALUES (?, ?)", name, sortOrder);
            }
        } catch (DuplicateKeyException e) {
            throw duplicate(t);
        }
        long id = jdbc.queryForObject("SELECT id FROM " + t.table + " WHERE name = ?", Long.class, name);
        Item created = require(t, id);
        audit.record(AuditAction.CATALOG_CREATED, t.table, id, null, null, created);
        return created;
    }

    @Transactional
    public Item update(Type t, long id, SaveRequest req) {
        Item before = require(t, id);
        Fields f = validate(t, req);
        String name = f.name();
        String symbol = f.symbol();
        int sortOrder = req.sortOrder() != null ? req.sortOrder() : before.sortOrder();
        try {
            if (t.hasSymbol) {
                jdbc.update("UPDATE " + t.table + " SET name = ?, symbol = ?, sort_order = ?, "
                        + "updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = ?", name, symbol, sortOrder, id);
            } else {
                jdbc.update("UPDATE " + t.table + " SET name = ?, sort_order = ?, "
                        + "updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = ?", name, sortOrder, id);
            }
        } catch (DuplicateKeyException e) {
            throw duplicate(t);
        }
        Item after = require(t, id);
        audit.record(AuditAction.CATALOG_UPDATED, t.table, id, null, before, after);
        return after;
    }

    @Transactional
    public Item setActive(Type t, long id, boolean active) {
        Item before = require(t, id);
        if (before.active() == active) {
            return before;
        }
        jdbc.update("UPDATE " + t.table + " SET active = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = ?",
                active ? 1 : 0, id);
        Item after = require(t, id);
        audit.record(AuditAction.CATALOG_STATUS_CHANGED, t.table, id, null, before, after);
        return after;
    }

    private static ApiException duplicate(Type t) {
        return ApiException.conflict("DUPLICATE_NAME",
                t.hasSymbol ? "Tên hoặc ký hiệu đã tồn tại." : "Tên đã tồn tại trong danh mục.");
    }
}
