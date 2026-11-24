package vn.trainingstats.user;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.trainingstats.audit.AuditAction;
import vn.trainingstats.audit.AuditService;
import vn.trainingstats.common.Times;
import vn.trainingstats.common.VietnameseText;
import vn.trainingstats.unit.UnitService;

/**
 * Bản ghi người dùng đại diện cho một đơn vị (kind = UNIT, ARCHITECTURE 2.7 và R19). Không có màn hình tạo tay:
 * hệ thống tự tạo khi lần đầu đưa đơn vị vào lớp hoặc vào một đề nghị. Mỗi đơn vị có đúng một bản ghi (chỉ mục
 * duy nhất uq_app_user_unit_kind). Bản ghi này không có tài khoản, luôn là USER.
 */
@Service
public class UnitRecordService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public UnitRecordService(JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    /** Trả id bản ghi đại diện của đơn vị, tạo mới nếu chưa có. Phải gọi trong giao dịch ghi đang mở. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long findOrCreate(UnitService.UnitView unit, long actorId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM app_user WHERE kind = 'UNIT' AND unit_id = ?", Long.class,
                unit.id());
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        String now = Times.now(clock);
        jdbc.update("""
                INSERT INTO app_user (kind, search_text, unit_id, role, status, created_at, created_by, updated_at, updated_by)
                VALUES ('UNIT', ?, ?, 'USER', 'ACTIVE', ?, ?, ?, ?)
                """, VietnameseText.searchKey(unit.name(), unit.code()), unit.id(), now, actorId, now, actorId);
        long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        audit.record(AuditAction.UNIT_RECORD_CREATED, "app_user", id, unit.code(), null,
                Map.of("kind", "UNIT", "unitCode", unit.code(), "unitName", unit.name()));
        return id;
    }
}
