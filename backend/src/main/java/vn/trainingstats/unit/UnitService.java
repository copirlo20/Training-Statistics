package vn.trainingstats.unit;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.trainingstats.audit.AuditAction;
import vn.trainingstats.audit.AuditService;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.JdbcValues;
import vn.trainingstats.common.Times;
import vn.trainingstats.common.Validation;
import vn.trainingstats.security.ScopeService;

/**
 * Cây đơn vị (tối đa 2 cấp). Mã chứa đường dẫn: cấp 1 là [xxxx], cấp 2 là [mã cha].[xxxx]. Mã, cấp, đơn vị cha
 * không đổi được sau khi tạo (DB có trigger chặn), chỉ đổi được tên, thứ tự và trạng thái.
 */
@Service
public class UnitService {

    public record UnitView(long id, String code, String name, Long parentId, int level, int sortOrder, boolean active) {
    }

    public record CreateRequest(Long parentId, String segment, String name, Integer sortOrder) {
    }

    public record UpdateRequest(String name, Integer sortOrder) {
    }

    private static final Pattern SEGMENT = Pattern.compile("[0-9A-Z]{4}");
    private static final RowMapper<UnitView> MAPPER = (rs, i) -> new UnitView(rs.getLong("id"), rs.getString("code"),
            rs.getString("name"), JdbcValues.nullableLong(rs, "parent_id"), rs.getInt("level"), rs.getInt("sort_order"),
            rs.getInt("active") == 1);

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ScopeService scopes;
    private final Clock clock;

    public UnitService(JdbcTemplate jdbc, AuditService audit, ScopeService scopes, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.scopes = scopes;
        this.clock = clock;
    }

    /** Toàn bộ cây (cả đơn vị đã khóa), sắp theo thứ tự hiển thị. Dùng cho ô chọn đơn vị và trang quản lý. */
    public List<UnitView> tree() {
        return jdbc.query("SELECT id, code, name, parent_id, level, sort_order, active FROM unit ORDER BY sort_order, code",
                MAPPER);
    }

    public Optional<UnitView> find(long id) {
        return jdbc.query("SELECT id, code, name, parent_id, level, sort_order, active FROM unit WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public UnitView require(long id) {
        return find(id).orElseThrow(() -> ApiException.notFound("Không tìm thấy đơn vị."));
    }

    @Transactional
    public UnitView create(CreateRequest req) {
        Validation v = new Validation();
        String name = v.required("name", req.name(), 100, "Nhập tên đơn vị.");
        String segment = Validation.trimToNull(req.segment());
        if (segment != null) {
            segment = v.pattern("segment", segment.toUpperCase(Locale.ROOT), SEGMENT,
                    "Mã gồm đúng 4 ký tự chữ in hoặc số.");
        }
        UnitView parent = null;
        if (req.parentId() != null) {
            parent = find(req.parentId()).orElse(null);
            if (parent == null) {
                v.add("parentId", "Không tìm thấy đơn vị cha.");
            } else if (parent.level() != 1) {
                v.add("parentId", "Chỉ tạo được đơn vị tối đa 2 cấp: đơn vị cha phải là cấp 1.");
            }
        }
        v.throwIfAny();

        if (segment == null) {
            segment = nextSegment(parent);
        }
        String code = parent == null ? segment : parent.code() + "." + segment;
        int level = parent == null ? 1 : 2;
        int sortOrder = req.sortOrder() != null ? req.sortOrder() : nextSortOrder();
        String now = Times.now(clock);
        long actor = scopes.currentPrincipal().userId();
        try {
            jdbc.update("""
                    INSERT INTO unit (code, name, parent_id, level, sort_order, created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, code, name, parent == null ? null : parent.id(), level, sortOrder, now, actor, now, actor);
        } catch (DuplicateKeyException e) {
            throw ApiException.invalidField("segment", "Mã đơn vị " + code + " đã tồn tại.");
        }
        UnitView created = jdbc.queryForObject(
                "SELECT id, code, name, parent_id, level, sort_order, active FROM unit WHERE code = ?", MAPPER, code);
        audit.record(AuditAction.UNIT_CREATED, "unit", created.id(), created.code(), null, created);
        return created;
    }

    @Transactional
    public UnitView update(long id, UpdateRequest req) {
        UnitView before = require(id);
        Validation v = new Validation();
        String name = v.required("name", req.name(), 100, "Nhập tên đơn vị.");
        v.throwIfAny();
        int sortOrder = req.sortOrder() != null ? req.sortOrder() : before.sortOrder();
        jdbc.update("UPDATE unit SET name = ?, sort_order = ?, updated_at = ?, updated_by = ? WHERE id = ?",
                name, sortOrder, Times.now(clock), scopes.currentPrincipal().userId(), id);
        UnitView after = require(id);
        audit.record(AuditAction.UNIT_UPDATED, "unit", id, after.code(), before, after);
        return after;
    }

    /** Khóa đơn vị: không chọn được khi tạo hay chuyển người dùng. Người đang thuộc đơn vị không bị ảnh hưởng. */
    @Transactional
    public UnitView setActive(long id, boolean active) {
        UnitView before = require(id);
        if (before.active() == active) {
            return before;
        }
        jdbc.update("UPDATE unit SET active = ?, updated_at = ?, updated_by = ? WHERE id = ?",
                active ? 1 : 0, Times.now(clock), scopes.currentPrincipal().userId(), id);
        UnitView after = require(id);
        audit.record(AuditAction.UNIT_STATUS_CHANGED, "unit", id, after.code(), before, after);
        return after;
    }

    /** Số thứ tự kế tiếp trong cùng cấp, dạng 4 chữ số. Bỏ qua mã có chữ (do người dùng tự đặt). */
    private String nextSegment(UnitView parent) {
        Integer max = parent == null
                ? jdbc.queryForObject("""
                        SELECT MAX(CAST(code AS INTEGER)) FROM unit
                        WHERE level = 1 AND code GLOB '[0-9][0-9][0-9][0-9]'
                        """, Integer.class)
                : jdbc.queryForObject("""
                        SELECT MAX(CAST(substr(code, 6) AS INTEGER)) FROM unit
                        WHERE parent_id = ? AND substr(code, 6) GLOB '[0-9][0-9][0-9][0-9]'
                        """, Integer.class, parent.id());
        int next = (max == null ? 0 : max) + 1;
        if (next > 9999) {
            throw ApiException.invalidField("segment", "Đã hết mã tự động, hãy nhập mã thủ công.");
        }
        return String.format("%04d", next);
    }

    private int nextSortOrder() {
        Integer max = jdbc.queryForObject("SELECT MAX(sort_order) FROM unit", Integer.class);
        return (max == null ? 0 : max) + 1;
    }
}
