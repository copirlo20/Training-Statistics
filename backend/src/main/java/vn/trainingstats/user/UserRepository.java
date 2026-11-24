package vn.trainingstats.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.trainingstats.common.JdbcValues;
import vn.trainingstats.common.PageQuery;
import vn.trainingstats.common.PageResult;
import vn.trainingstats.common.VietnameseText;
import vn.trainingstats.security.Scope;
import vn.trainingstats.security.SqlCondition;
import vn.trainingstats.user.UserDtos.RankRef;
import vn.trainingstats.user.UserDtos.Ref;
import vn.trainingstats.user.UserDtos.UnitRef;
import vn.trainingstats.user.UserDtos.UserListItem;
import vn.trainingstats.user.UserDtos.UserView;

/** Truy vấn người dùng. Mọi truy vấn danh sách nhận {@link Scope}; truy vấn theo id được service kiểm phạm vi. */
@Repository
public class UserRepository {

    /** Dữ liệu thô dùng để kiểm quyền trước khi thao tác. */
    public record Target(long id, String kind, String role, String status, Long unitId, String unitCode,
                         String username, boolean hasPassword, int version) {
    }

    /**
     * Bộ lọc danh sách. Mỗi danh sách rỗng nghĩa là không lọc theo tiêu chí đó; nhiều giá trị trong một danh sách là
     * "hoặc" (đơn vị gồm cả đơn vị con). {@code statuses} rỗng nghĩa là mọi trạng thái trừ DELETED.
     */
    public record Filter(String q, List<String> unitCodes, List<Long> rankIds, List<Long> positionIds,
                         List<Long> specialtyIds, List<String> statuses, List<String> roles, String kind,
                         String sort, String dir, List<String> categories) {
        public Filter {
            categories = categories == null ? List.of() : List.copyOf(categories);
            unitCodes = unitCodes == null ? List.of() : List.copyOf(unitCodes);
            rankIds = rankIds == null ? List.of() : List.copyOf(rankIds);
            positionIds = positionIds == null ? List.of() : List.copyOf(positionIds);
            specialtyIds = specialtyIds == null ? List.of() : List.copyOf(specialtyIds);
            statuses = statuses == null ? List.of() : List.copyOf(statuses);
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    private static final Map<String, String> SORTS = Map.of(
            "name", "u.first_name COLLATE NOCASE %1$s, u.last_name COLLATE NOCASE %1$s",
            "code", "u.personal_code %1$s",
            "unit", "un.code %1$s, u.first_name COLLATE NOCASE",
            "rank", "r.sort_order %1$s, u.first_name COLLATE NOCASE",
            "status", "u.status %1$s, u.first_name COLLATE NOCASE");

    private static final String VIEW_SELECT = """
            SELECT u.id, u.kind, u.last_name, u.first_name, u.personal_code, u.category, u.username, u.role, u.status,
                   u.birth_date, u.phone, u.note, u.last_login_at, u.version, u.created_at, u.updated_at,
                   CASE WHEN u.password_hash IS NULL THEN 0 ELSE 1 END AS has_password,
                   r.id AS rank_id, r.name AS rank_name, r.symbol AS rank_symbol,
                   p.id AS position_id, p.name AS position_name,
                   un.id AS unit_id, un.code AS unit_code, un.name AS unit_name, un.level AS unit_level,
                   pu.name AS parent_unit_name
            FROM app_user u
            LEFT JOIN rank r ON r.id = u.rank_id
            LEFT JOIN position p ON p.id = u.position_id
            LEFT JOIN unit un ON un.id = u.unit_id
            LEFT JOIN unit pu ON pu.id = un.parent_id
            """;

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Người theo id trong phạm vi {@code scope} (điều kiện phạm vi nằm trong SQL); ngoài phạm vi thì rỗng. */
    public Optional<Target> findTarget(long id, Scope scope) {
        SqlCondition c = scope.userFilter("u.id", "un.code");
        return jdbc.query("""
                SELECT u.id, u.kind, u.role, u.status, u.unit_id, un.code AS unit_code, u.username,
                       CASE WHEN u.password_hash IS NULL THEN 0 ELSE 1 END AS has_password, u.version
                FROM app_user u LEFT JOIN unit un ON un.id = u.unit_id WHERE u.id = ? AND\s""" + c.sql(),
                (rs, i) -> target(rs), c.paramsAfter(id))
                .stream().findFirst();
    }

    private static Target target(ResultSet rs) throws SQLException {
        return new Target(rs.getLong("id"), rs.getString("kind"), rs.getString("role"), rs.getString("status"),
                JdbcValues.nullableLong(rs, "unit_id"), rs.getString("unit_code"), rs.getString("username"),
                rs.getInt("has_password") == 1, rs.getInt("version"));
    }

    /** Người có mã định danh này, kèm họ tên, tên đơn vị hiện tại và đối tượng (để đối chiếu khi nhập Excel, R8). */
    public record CodeMatch(Target target, String fullName, String unitName, String category) {
    }

    /**
     * Tìm theo mã định danh trên toàn Lữ đoàn, có chủ đích không kèm phạm vi: mã là duy nhất toàn hệ thống, nhập Excel
     * phải phát hiện mã đã có ở nhánh khác để báo lỗi (không lộ tên). Người gọi kiểm phạm vi trên {@code target} trước
     * khi dùng hay hiển thị.
     */
    public Optional<CodeMatch> findByCode(String personalCode) {
        return jdbc.query("""
                SELECT u.id, u.kind, u.role, u.status, u.unit_id, un.code AS unit_code, un.name AS unit_name, u.username,
                       CASE WHEN u.password_hash IS NULL THEN 0 ELSE 1 END AS has_password, u.version,
                       u.last_name, u.first_name, u.category
                FROM app_user u LEFT JOIN unit un ON un.id = u.unit_id WHERE u.personal_code = ?
                """, (rs, i) -> new CodeMatch(target(rs), fullName(rs.getString("last_name"), rs.getString("first_name"), rs.getString("unit_name")),
                rs.getString("unit_name"), rs.getString("category")), personalCode).stream().findFirst();
    }

    /**
     * Id người đang dùng tài khoản này (không phân biệt hoa thường), có chủ đích không kèm phạm vi: tên tài khoản là
     * duy nhất toàn hệ thống. Chỉ để kiểm trùng, không trả thông tin người đó.
     */
    public Optional<Long> idByUsername(String username) {
        return jdbc.queryForList("SELECT id FROM app_user WHERE username = ?", Long.class, username).stream().findFirst();
    }

    /** Người dùng cá nhân đang hoạt động, có đơn vị: mã, họ tên, tên đơn vị (ô chọn người học trong file mẫu nhập lớp). */
    public record PersonOption(String personalCode, String fullName, String unitName) {
    }

    public List<PersonOption> activePeople(Scope scope) {
        SqlCondition c = scope.userFilter("u.id", "un.code");
        return jdbc.query("""
                SELECT u.personal_code, u.last_name, u.first_name, un.name AS unit_name
                FROM app_user u JOIN unit un ON un.id = u.unit_id
                WHERE u.kind = 'PERSON' AND u.status = 'ACTIVE' AND u.personal_code IS NOT NULL AND\s""" + c.sql() + """
                 ORDER BY un.code, u.first_name, u.last_name, u.id
                """, (rs, i) -> new PersonOption(rs.getString("personal_code"),
                fullName(rs.getString("last_name"), rs.getString("first_name"), null), rs.getString("unit_name")),
                c.params().toArray());
    }

    /** Chi tiết người theo id trong phạm vi {@code scope} (điều kiện phạm vi nằm trong SQL). */
    public Optional<UserView> findView(long id, Scope scope) {
        SqlCondition c = scope.userFilter("u.id", "un.code");
        List<UserView> rows = jdbc.query(VIEW_SELECT + " WHERE u.id = ? AND " + c.sql(),
                (rs, i) -> view(rs, specialties(rs.getLong("id"))), c.paramsAfter(id));
        return rows.stream().findFirst();
    }

    private List<Ref> specialties(long userId) {
        return jdbc.query("""
                SELECT s.id, s.name FROM user_specialty us JOIN specialty s ON s.id = us.specialty_id
                WHERE us.user_id = ? ORDER BY s.sort_order, s.name
                """, (rs, i) -> new Ref(rs.getLong("id"), rs.getString("name")), userId);
    }

    public PageResult<UserListItem> search(Scope scope, Filter f, PageQuery page) {
        StringBuilder where = new StringBuilder(" WHERE u.kind = ?");
        List<Object> params = new ArrayList<>();
        params.add(f.kind() == null ? "PERSON" : f.kind());

        SqlCondition sc = scope.userFilter("u.id", "un.code");
        where.append(" AND ").append(sc.sql());
        params.addAll(sc.params());

        if (f.statuses().isEmpty()) {
            where.append(" AND u.status <> 'DELETED'");
        } else if (!f.statuses().contains("ALL")) {
            SqlCondition.in("u.status", f.statuses()).appendTo(where, params);
        }
        if (f.q() != null && !VietnameseText.searchKey(f.q()).isEmpty()) {
            where.append(" AND u.search_text LIKE ? ESCAPE '\\'");
            params.add(VietnameseText.likeContains(f.q()));
        }
        if (!f.unitCodes().isEmpty()) {
            SqlCondition.withinAny("un.code", f.unitCodes()).appendTo(where, params);
        }
        if (!f.rankIds().isEmpty()) {
            SqlCondition.in("u.rank_id", f.rankIds()).appendTo(where, params);
        }
        if (!f.positionIds().isEmpty()) {
            SqlCondition.in("u.position_id", f.positionIds()).appendTo(where, params);
        }
        if (!f.specialtyIds().isEmpty()) {
            UserSql.hasAnySpecialty("u.id", f.specialtyIds()).appendTo(where, params);
        }
        if (!f.categories().isEmpty()) {
            SqlCondition.in("u.category", f.categories()).appendTo(where, params);
        }
        if (!f.roles().isEmpty()) {
            SqlCondition.in("u.role", f.roles()).appendTo(where, params);
        }

        String from = " FROM app_user u LEFT JOIN rank r ON r.id = u.rank_id LEFT JOIN position p ON p.id = u.position_id"
                + " LEFT JOIN unit un ON un.id = u.unit_id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, params.toArray());

        String dir = "desc".equalsIgnoreCase(f.dir()) ? "DESC" : "ASC";
        String order = String.format(SORTS.getOrDefault(f.sort() == null ? "unit" : f.sort(), SORTS.get("unit")), dir);
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(page.size());
        pageParams.add(page.offset());
        List<UserListItem> items = jdbc.query("""
                SELECT u.id, u.kind, u.last_name, u.first_name, u.personal_code, u.category, u.username, u.role, u.status,
                       r.symbol AS rank_symbol, r.name AS rank_name, p.name AS position_name,
                       un.id AS unit_id, un.code AS unit_code, un.name AS unit_name
                """ + from + where + " ORDER BY " + order + ", u.id LIMIT ? OFFSET ?",
                (rs, i) -> new UserListItem(rs.getLong("id"), rs.getString("kind"),
                        fullName(rs.getString("last_name"), rs.getString("first_name"), rs.getString("unit_name")),
                        rs.getString("personal_code"), rs.getString("category"), rs.getString("rank_symbol"), rs.getString("rank_name"),
                        rs.getString("position_name"), JdbcValues.nullableLong(rs, "unit_id"), rs.getString("unit_code"),
                        rs.getString("unit_name"), rs.getString("username"), rs.getString("role"), rs.getString("status")),
                pageParams.toArray());
        return page.result(items, total == null ? 0 : total);
    }

    public void replaceSpecialties(long userId, List<Long> specialtyIds) {
        jdbc.update("DELETE FROM user_specialty WHERE user_id = ?", userId);
        for (Long sid : specialtyIds) {
            jdbc.update("INSERT INTO user_specialty (user_id, specialty_id) VALUES (?, ?)", userId, sid);
        }
    }

    static String fullName(String lastName, String firstName, String unitName) {
        if (lastName == null && firstName == null) {
            return unitName == null ? "" : unitName;
        }
        return ((lastName == null ? "" : lastName) + " " + (firstName == null ? "" : firstName)).trim();
    }

    private static UserView view(ResultSet rs, List<Ref> specialties) throws SQLException {
        Long rankId = JdbcValues.nullableLong(rs, "rank_id");
        Long positionId = JdbcValues.nullableLong(rs, "position_id");
        Long unitId = JdbcValues.nullableLong(rs, "unit_id");
        String role = rs.getString("role");
        UnitRef unit = null;
        if (unitId != null) {
            String parent = rs.getString("parent_unit_name");
            String path = parent == null ? rs.getString("unit_name") : parent + " / " + rs.getString("unit_name");
            unit = new UnitRef(unitId, rs.getString("unit_code"), rs.getString("unit_name"), rs.getInt("unit_level"), path);
        }
        return new UserView(
                rs.getLong("id"), rs.getString("kind"), rs.getString("last_name"), rs.getString("first_name"),
                fullName(rs.getString("last_name"), rs.getString("first_name"), rs.getString("unit_name")),
                rs.getString("personal_code"), rs.getString("category"),
                rankId == null ? null : new RankRef(rankId, rs.getString("rank_name"), rs.getString("rank_symbol")),
                positionId == null ? null : new Ref(positionId, rs.getString("position_name")),
                unit, specialties, rs.getString("username"), rs.getInt("has_password") == 1, role,
                "ADMIN".equals(role) && unit != null ? unit.level() : null,
                rs.getString("status"), rs.getString("birth_date"), rs.getString("phone"), rs.getString("note"),
                rs.getString("last_login_at"), rs.getInt("version"), rs.getString("created_at"), rs.getString("updated_at"));
    }
}
