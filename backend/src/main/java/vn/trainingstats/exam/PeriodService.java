package vn.trainingstats.exam;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.trainingstats.audit.AuditAction;
import vn.trainingstats.audit.AuditService;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.OptimisticLock;
import vn.trainingstats.common.Times;
import vn.trainingstats.common.Validation;
import vn.trainingstats.security.ScopeService;

/**
 * Đợt kiểm tra. Chỉ Super Admin thêm, sửa, đóng, mở, xóa mềm. Mọi người đã đăng nhập đọc được danh sách đợt
 * (chỉ tên và ngày, dùng để lọc).
 * <ul>
 *   <li>Đợt đóng không nhận lớp mới. Lớp đã có trong đợt không bị ảnh hưởng.</li>
 *   <li>Chỉ xóa mềm được đợt không còn lớp nào (chưa xóa), để không có lớp mồ côi.</li>
 * </ul>
 */
@Service
public class PeriodService {

    public record PeriodView(long id, String name, String startDate, String endDate, String status, String note,
                             int classCount, int version) {
    }

    public record SaveRequest(String name, String startDate, String endDate, String note, Integer version) {
    }

    private static final RowMapper<PeriodView> MAPPER = (rs, i) -> new PeriodView(rs.getLong("id"),
            rs.getString("name"), rs.getString("start_date"), rs.getString("end_date"), rs.getString("status"),
            rs.getString("note"), rs.getInt("class_count"), rs.getInt("version"));

    private static final String SELECT = """
            SELECT p.id, p.name, p.start_date, p.end_date, p.status, p.note, p.version,
                   (SELECT COUNT(*) FROM exam_class c WHERE c.period_id = p.id AND c.deleted_at IS NULL) AS class_count
            FROM exam_period p
            WHERE p.deleted_at IS NULL
            """;

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ScopeService scopes;
    private final Clock clock;

    public PeriodService(JdbcTemplate jdbc, AuditService audit, ScopeService scopes, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.scopes = scopes;
        this.clock = clock;
    }

    public List<PeriodView> list() {
        return jdbc.query(SELECT + " ORDER BY (p.start_date IS NULL), p.start_date DESC, p.id DESC", MAPPER);
    }

    public Optional<PeriodView> find(long id) {
        return jdbc.query(SELECT + " AND p.id = ?", MAPPER, id).stream().findFirst();
    }

    public PeriodView require(long id) {
        return find(id).orElseThrow(() -> ApiException.notFound("Không tìm thấy đợt kiểm tra."));
    }

    @Transactional
    public PeriodView create(SaveRequest req) {
        Fields f = validate(req);
        String now = Times.now(clock);
        long actor = scopes.currentPrincipal().userId();
        jdbc.update("""
                INSERT INTO exam_period (name, start_date, end_date, note, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, f.name, f.startDate, f.endDate, f.note, now, actor, now, actor);
        long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        PeriodView created = require(id);
        audit.record(AuditAction.PERIOD_CREATED, "exam_period", id, null, null, created);
        return created;
    }

    @Transactional
    public PeriodView update(long id, SaveRequest req) {
        PeriodView before = require(id);
        Fields f = validate(req);
        if (req.version() == null) {
            throw ApiException.invalidField("version", "Thiếu phiên bản dữ liệu, hãy tải lại trang.");
        }
        int n = jdbc.update("""
                UPDATE exam_period SET name = ?, start_date = ?, end_date = ?, note = ?, updated_at = ?, updated_by = ?,
                       version = version + 1
                WHERE id = ? AND version = ? AND deleted_at IS NULL
                """, f.name, f.startDate, f.endDate, f.note, Times.now(clock), scopes.currentPrincipal().userId(),
                id, req.version());
        OptimisticLock.check(n);
        PeriodView after = require(id);
        audit.record(AuditAction.PERIOD_UPDATED, "exam_period", id, null, before, after);
        return after;
    }

    @Transactional
    public PeriodView setStatus(long id, String status) {
        PeriodView before = require(id);
        if (!"OPEN".equals(status) && !"CLOSED".equals(status)) {
            throw ApiException.invalidField("status", "Trạng thái phải là OPEN hoặc CLOSED.");
        }
        if (status.equals(before.status())) {
            return before;
        }
        jdbc.update("UPDATE exam_period SET status = ?, updated_at = ?, updated_by = ?, version = version + 1 WHERE id = ?",
                status, Times.now(clock), scopes.currentPrincipal().userId(), id);
        audit.record(AuditAction.PERIOD_STATUS_CHANGED, "exam_period", id, null,
                Map.of("status", before.status()), Map.of("status", status));
        return require(id);
    }

    @Transactional
    public void delete(long id) {
        PeriodView before = require(id);
        if (before.classCount() > 0) {
            throw ApiException.conflict("PERIOD_HAS_CLASSES",
                    "Đợt còn " + before.classCount() + " nội dung kiểm tra. Xóa hoặc chuyển các nội dung kiểm tra trước khi xóa đợt.");
        }
        String now = Times.now(clock);
        long actor = scopes.currentPrincipal().userId();
        jdbc.update("""
                UPDATE exam_period SET deleted_at = ?, deleted_by = ?, updated_at = ?, updated_by = ?, version = version + 1
                WHERE id = ?
                """, now, actor, now, actor, id);
        audit.record(AuditAction.PERIOD_DELETED, "exam_period", id, null, before, null);
    }

    private record Fields(String name, String startDate, String endDate, String note) {
    }

    private static Fields validate(SaveRequest req) {
        Validation v = new Validation();
        String name = v.required("name", req.name(), 100, "Nhập tên đợt kiểm tra.");
        String start = v.date("startDate", req.startDate());
        String end = v.date("endDate", req.endDate());
        String note = v.optional("note", req.note(), 500);
        if (start != null && end != null && end.compareTo(start) < 0) {
            v.add("endDate", "Ngày kết thúc phải sau hoặc bằng ngày bắt đầu.");
        }
        v.throwIfAny();
        return new Fields(name, start, end, note);
    }
}
