package vn.trainingstats.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.trainingstats.common.JdbcValues;
import vn.trainingstats.common.PageQuery;
import vn.trainingstats.common.PageResult;
import vn.trainingstats.common.VietnameseText;

@Repository
public class AuditRepository {

    /** Điều kiện lọc. Thời gian là chuỗi UTC đã đổi từ ngày theo giờ Việt Nam; {@code toExclusive} là cận trên mở. */
    public record Filter(Long actorId, String actor, String action, String from, String toExclusive) {
    }

    public record Row(long id, String at, Long actorId, String actorUsername, String action, String entityType,
                      Long entityId, String unitCode, String beforeJson, String afterJson, String ip) {
    }

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResult<Row> search(Filter f, PageQuery page) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (f.actorId() != null) {
            where.append(" AND actor_id = ?");
            params.add(f.actorId());
        }
        if (f.actor() != null && !f.actor().isBlank()) {
            where.append(" AND lower(actor_username) LIKE ? ESCAPE '\\'");
            params.add("%" + VietnameseText.escapeLike(f.actor().trim().toLowerCase(Locale.ROOT)) + "%");
        }
        if (f.action() != null && !f.action().isBlank()) {
            where.append(" AND action = ?");
            params.add(f.action().trim());
        }
        if (f.from() != null) {
            where.append(" AND at >= ?");
            params.add(f.from());
        }
        if (f.toExclusive() != null) {
            where.append(" AND at < ?");
            params.add(f.toExclusive());
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log" + where, Long.class, params.toArray());

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(page.size());
        pageParams.add(page.offset());
        List<Row> rows = jdbc.query("""
                SELECT id, at, actor_id, actor_username, action, entity_type, entity_id, unit_code,
                       before_json, after_json, ip
                FROM audit_log""" + where + " ORDER BY at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new Row(rs.getLong("id"), rs.getString("at"), JdbcValues.nullableLong(rs, "actor_id"),
                        rs.getString("actor_username"), rs.getString("action"), rs.getString("entity_type"),
                        JdbcValues.nullableLong(rs, "entity_id"), rs.getString("unit_code"), rs.getString("before_json"),
                        rs.getString("after_json"), rs.getString("ip")),
                pageParams.toArray());
        return page.result(rows, total == null ? 0 : total);
    }
}
