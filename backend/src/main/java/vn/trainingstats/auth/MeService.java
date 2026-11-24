package vn.trainingstats.auth;

import vn.trainingstats.common.JdbcValues;
import vn.trainingstats.exam.ClassStatusSql;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vn.trainingstats.security.UserPrincipal;
import vn.trainingstats.settings.AppSettings;

/** Thông tin của chính người đang đăng nhập. */
@Service
public class MeService {

    public record UnitInfo(long id, String code, String name, int level) {
    }

    public record RankInfo(String name, String symbol) {
    }

    public record TeachingClass(long id, String name, String status) {
    }

    /**
     * @param adminLevel            cấp admin (bằng cấp của đơn vị), null nếu không phải Admin
     * @param sessionTimeoutMinutes phiên hết hạn sau ngần ấy phút không thao tác. Frontend dùng để ngừng hỏi thông báo
     *                              định kỳ khi người dùng không thao tác, nếu không phiên sẽ không bao giờ hết hạn
     */
    public record Me(long id, String username, String displayName, String role, Integer adminLevel,
                     UnitInfo unit, RankInfo rank, String position, List<TeachingClass> teachingClasses,
                     int sessionTimeoutMinutes) {
    }

    private final JdbcTemplate jdbc;
    private final AppSettings settings;

    public MeService(JdbcTemplate jdbc, AppSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    public Me me(UserPrincipal p) {
        Me base = jdbc.queryForObject("""
                SELECT u.id, u.username, u.role,
                       un.id AS unit_id, un.code AS unit_code, un.name AS unit_name, un.level AS unit_level,
                       r.name AS rank_name, r.symbol AS rank_symbol, pos.name AS position_name
                FROM app_user u
                LEFT JOIN unit un ON un.id = u.unit_id
                LEFT JOIN rank r ON r.id = u.rank_id
                LEFT JOIN position pos ON pos.id = u.position_id
                WHERE u.id = ?
                """, (rs, i) -> {
            Long unitId = JdbcValues.nullableLong(rs, "unit_id");
            UnitInfo unit = unitId == null ? null : new UnitInfo(unitId, rs.getString("unit_code"),
                    rs.getString("unit_name"), rs.getInt("unit_level"));
            String rankName = rs.getString("rank_name");
            RankInfo rank = rankName == null ? null : new RankInfo(rankName, rs.getString("rank_symbol"));
            String role = rs.getString("role");
            Integer adminLevel = "ADMIN".equals(role) && unit != null ? unit.level() : null;
            return new Me(rs.getLong("id"), rs.getString("username"), p.displayName(), role, adminLevel, unit, rank,
                    rs.getString("position_name"), List.of(), 0);
        }, p.userId());

        List<TeachingClass> classes = jdbc.query("SELECT c.id, c.name, " + ClassStatusSql.effective("c", "p") + """
                 AS status FROM class_teacher t
                JOIN exam_class c ON c.id = t.class_id
                JOIN exam_period p ON p.id = c.period_id
                WHERE t.user_id = ? AND c.deleted_at IS NULL
                ORDER BY c.exam_date DESC, c.id DESC
                """, (rs, i) -> new TeachingClass(rs.getLong("id"), rs.getString("name"), rs.getString("status")),
                p.userId());

        return new Me(base.id(), base.username(), base.displayName(), base.role(), base.adminLevel(), base.unit(),
                base.rank(), base.position(), classes, settings.sessionTimeoutMinutes());
    }
}
