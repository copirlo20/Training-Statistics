package vn.trainingstats.request;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.trainingstats.common.JdbcValues;
import vn.trainingstats.common.PageQuery;
import vn.trainingstats.common.PageResult;
import vn.trainingstats.exam.ClassStatusSql;
import vn.trainingstats.request.RequestDtos.Access;
import vn.trainingstats.request.RequestDtos.ClassRef;
import vn.trainingstats.request.RequestDtos.PersonRef;
import vn.trainingstats.request.RequestDtos.RequestView;
import vn.trainingstats.request.RequestDtos.TargetRef;
import vn.trainingstats.security.UserPrincipal;
import vn.trainingstats.user.UserSql;

/**
 * Truy vấn đề nghị. Người gọi chỉ thấy: Super Admin tất cả; người khác đề nghị do mình tạo và đề nghị của lớp
 * mình làm giáo viên. Không thấy thì coi như không tồn tại.
 */
@Repository
public class RequestRepository {

    public record Row(long id, long classId, String type, long userId, long requestedBy, String status) {
    }

    private static final String SELECT = "SELECT cr.id, cr.type, cr.status, cr.created_at, cr.decided_at, cr.decision_note,"
            + " cr.requested_by,"
            + " c.id AS class_id, c.name AS class_name, " + ClassStatusSql.effective("c", "cpe") + " AS class_status,"
            + " u.id AS target_id, u.kind AS target_kind, " + UserSql.fullName("u", "un") + " AS target_name,"
            + " r.symbol AS target_rank, un.name AS target_unit, u.personal_code AS target_code,"
            + " " + UserSql.fullName("rq", "rqu") + " AS requester_name,"
            + " ru.name AS requested_unit_name,"
            + " d.id AS decider_id, " + UserSql.fullName("d", "du") + " AS decider_name,"
            + " EXISTS (SELECT 1 FROM class_teacher t WHERE t.class_id = cr.class_id AND t.user_id = ?) AS is_teacher"
            + """
             FROM class_request cr
            JOIN exam_class c ON c.id = cr.class_id
            JOIN exam_period cpe ON cpe.id = c.period_id
            JOIN app_user u ON u.id = cr.user_id
            LEFT JOIN unit un ON un.id = u.unit_id
            LEFT JOIN rank r ON r.id = u.rank_id
            JOIN app_user rq ON rq.id = cr.requested_by
            LEFT JOIN unit rqu ON rqu.id = rq.unit_id
            JOIN unit ru ON ru.id = cr.requested_unit_id
            LEFT JOIN app_user d ON d.id = cr.decided_by
            LEFT JOIN unit du ON du.id = d.unit_id
            """;

    private final JdbcTemplate jdbc;

    public RequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Điều kiện "người gọi thấy đề nghị cr". */
    private static String visible(UserPrincipal me, List<Object> params) {
        if (me.isSuperAdmin()) {
            return "1 = 1";
        }
        params.add(me.userId());
        params.add(me.userId());
        return "(cr.requested_by = ? OR EXISTS (SELECT 1 FROM class_teacher vt WHERE vt.class_id = cr.class_id"
                + " AND vt.user_id = ?))";
    }

    public Optional<Row> findRow(UserPrincipal me, long id) {
        List<Object> params = new ArrayList<>();
        params.add(id);
        String cond = visible(me, params);
        return jdbc.query("SELECT cr.id, cr.class_id, cr.type, cr.user_id, cr.requested_by, cr.status"
                        + " FROM class_request cr WHERE cr.id = ? AND " + cond,
                (rs, i) -> new Row(rs.getLong("id"), rs.getLong("class_id"), rs.getString("type"), rs.getLong("user_id"),
                        rs.getLong("requested_by"), rs.getString("status")), params.toArray()).stream().findFirst();
    }

    public Optional<RequestView> findView(UserPrincipal me, long id) {
        List<Object> params = new ArrayList<>();
        params.add(me.userId());
        params.add(id);
        String cond = visible(me, params);
        return jdbc.query(SELECT + " WHERE cr.id = ? AND " + cond, (rs, i) -> map(rs, me), params.toArray())
                .stream().findFirst();
    }

    public PageResult<RequestView> search(UserPrincipal me, String status, Long classId, PageQuery page) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE ").append(visible(me, params));
        if (status != null) {
            where.append(" AND cr.status = ?");
            params.add(status);
        }
        if (classId != null) {
            where.append(" AND cr.class_id = ?");
            params.add(classId);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM class_request cr" + where, Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>();
        pageParams.add(me.userId());
        pageParams.addAll(params);
        pageParams.add(page.size());
        pageParams.add(page.offset());
        List<RequestView> items = jdbc.query(SELECT + where + " ORDER BY cr.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> map(rs, me), pageParams.toArray());
        return page.result(items, total == null ? 0 : total);
    }

    private static RequestView map(ResultSet rs, UserPrincipal me) throws SQLException {
        boolean pending = "PENDING".equals(rs.getString("status"));
        boolean manager = me.isSuperAdmin() || rs.getInt("is_teacher") == 1;
        Long deciderId = JdbcValues.nullableLong(rs, "decider_id");
        long requestedBy = rs.getLong("requested_by");
        return new RequestView(rs.getLong("id"),
                new ClassRef(rs.getLong("class_id"), rs.getString("class_name"), rs.getString("class_status")),
                rs.getString("type"),
                new TargetRef(rs.getLong("target_id"), rs.getString("target_kind"), rs.getString("target_name"),
                        rs.getString("target_rank"), rs.getString("target_unit"), rs.getString("target_code")),
                new PersonRef(requestedBy, rs.getString("requester_name")),
                rs.getString("requested_unit_name"), rs.getString("status"), rs.getString("created_at"),
                deciderId == null ? null : new PersonRef(deciderId, rs.getString("decider_name")),
                rs.getString("decided_at"), rs.getString("decision_note"),
                new Access(pending && manager, pending && requestedBy == me.userId()));
    }
}
