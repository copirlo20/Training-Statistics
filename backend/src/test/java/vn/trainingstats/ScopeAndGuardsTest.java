package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.security.ClassAccess;
import vn.trainingstats.security.Scope;
import vn.trainingstats.security.ScopeService;
import vn.trainingstats.security.SqlCondition;
import vn.trainingstats.security.UnitCodes;
import vn.trainingstats.security.UserGuards;
import vn.trainingstats.security.UserPrincipal;
import vn.trainingstats.security.UserPrincipal.Role;

/** Phạm vi theo nhánh, kiểm tra giáo viên của lớp, quy tắc chặn tự thao tác và Super Admin cuối cùng. */
@Transactional
class ScopeAndGuardsTest extends DbTestBase {

    @Autowired
    ScopeService scopes;
    @Autowired
    ClassAccess classAccess;
    @Autowired
    UserGuards guards;

    private static UserPrincipal principal(long id, Role role, String unitCode, Integer level) {
        return new UserPrincipal(id, "u" + id, role, unitCode == null ? null : 1L, unitCode, level, "Test", false);
    }

    // ------------------------------------------------------------------ phạm vi

    @Test
    void phamViTheoVaiTro() {
        assertThat(scopes.of(principal(1, Role.SUPER_ADMIN, null, null)).kind()).isEqualTo(Scope.Kind.ALL);
        Scope admin = scopes.of(principal(2, Role.ADMIN, "0002", 1));
        assertThat(admin.kind()).isEqualTo(Scope.Kind.UNIT_TREE);
        assertThat(admin.unitCode()).isEqualTo("0002");
        Scope user = scopes.of(principal(3, Role.USER, "0002.0001", 2));
        assertThat(user.kind()).isEqualTo(Scope.Kind.SELF);
        assertThat(user.userId()).isEqualTo(3);
    }

    @Test
    void cungNhanhKhongKhopNham() {
        assertThat(UnitCodes.isWithin("0002", "0002")).isTrue();
        assertThat(UnitCodes.isWithin("0002.0001", "0002")).isTrue();
        assertThat(UnitCodes.isWithin("00021", "0002")).as("00021 không thuộc 0002").isFalse();
        assertThat(UnitCodes.isWithin("0002x", "0002")).isFalse();
        assertThat(UnitCodes.isWithin("0003", "0002")).isFalse();
        assertThat(UnitCodes.isWithin("0002", "0002.0001")).as("cấp trên không thuộc cấp dưới").isFalse();
        assertThat(UnitCodes.isWithin(null, "0002")).isFalse();
    }

    @Test
    void dieuKienSqlTuongDuongDieuKienJava() {
        String[] roots = {"0002", "0002.0001", "a_b", "x%y"};
        String[] candidates = {"0002", "0002.0001", "0002.0002", "00021", "00021.0001", "0003", "0002.00011",
                "a_b", "a_b.1", "axb", "axb.1", "x%y.1", "xzy.1", ""};
        for (String root : roots) {
            SqlCondition cond = UnitCodes.within("t.code", root);
            for (String candidate : candidates) {
                List<Object> params = new ArrayList<>();
                params.add(candidate);
                params.addAll(cond.params());
                Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT ? AS code) t WHERE " + cond.sql(),
                        Integer.class, params.toArray());
                assertThat(n == 1).as("SQL: '%s' thuộc '%s'", candidate, root)
                        .isEqualTo(UnitCodes.isWithin(candidate, root));
            }
        }
    }

    @Test
    void dieuKienTheoPhamViTrenDuLieuThat() {
        // Admin của 0002 thấy 0002 và 5 đơn vị con, không thấy đơn vị khác.
        Scope admin = Scope.unitTree("0002", 99);
        SqlCondition c = admin.unitFilter("code");
        List<String> codes = jdbc.queryForList("SELECT code FROM unit WHERE " + c.sql() + " ORDER BY code",
                String.class, c.params().toArray());
        assertThat(codes).containsExactly("0002", "0002.0001", "0002.0002", "0002.0003", "0002.0004", "0002.0005");

        assertThat(Scope.all(1).unitFilter("code")).isEqualTo(SqlCondition.ALWAYS);
        assertThat(Scope.self(5).unitFilter("code")).isEqualTo(SqlCondition.NEVER);
        SqlCondition self = Scope.self(5).userFilter("u.id", "un.code");
        assertThat(self.sql()).isEqualTo("u.id = ?");
        assertThat(self.params()).containsExactly(5L);

        assertThat(admin.coversUser(7, "0002.0003")).isTrue();
        assertThat(admin.coversUser(7, "0003")).isFalse();
        assertThat(Scope.self(5).coversUser(5, "0003")).isTrue();
        assertThat(Scope.self(5).coversUser(6, "0003")).isFalse();
        assertThat(Scope.self(5).coversUnit("0003")).isFalse();
    }

    // ------------------------------------------------------------------ giáo viên của lớp

    private long user(String code, String role, String username) {
        jdbc.update("INSERT INTO app_user(kind, last_name, first_name, search_text, unit_id, role, username, password_hash, personal_code) "
                        + "VALUES ('PERSON', 'Le', 'Binh', 'le binh', (SELECT id FROM unit WHERE code = ?), ?, ?, ?, ?)",
                code, role, username, username == null ? null : "hash", "P-" + System.nanoTime());
        return jdbc.queryForObject("SELECT MAX(id) FROM app_user", Long.class);
    }

    private long superAdmin(String username) {
        jdbc.update("INSERT INTO app_user(kind, last_name, first_name, search_text, role, username, password_hash, personal_code) "
                + "VALUES ('PERSON', 'Quan', 'Tri', 'quan tri', 'SUPER_ADMIN', ?, 'hash', ?)", username, "SA-" + username);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
    }

    private long newClass() {
        jdbc.update("INSERT INTO exam_period(name) VALUES ('Dot test')");
        long period = jdbc.queryForObject("SELECT MAX(id) FROM exam_period", Long.class);
        jdbc.update("INSERT INTO exam_class(period_id, name) VALUES (?, 'Lop test')", period);
        return jdbc.queryForObject("SELECT MAX(id) FROM exam_class", Long.class);
    }

    @Test
    void giaoVienChiQuanLyLopDuocGan() {
        long teacher = user("0002.0001", "USER", "gv1");
        long other = user("0003", "USER", "gv2");
        long clazz = newClass();
        long otherClass = newClass();
        jdbc.update("INSERT INTO class_teacher(class_id, user_id) VALUES (?, ?)", clazz, teacher);

        assertThat(classAccess.isTeacher(teacher, clazz)).isTrue();
        assertThat(classAccess.isTeacher(teacher, otherClass)).isFalse();
        assertThat(classAccess.isTeacher(other, clazz)).isFalse();

        UserPrincipal teacherP = principal(teacher, Role.USER, "0002.0001", 2);
        assertThatCode(() -> classAccess.requireManage(teacherP, clazz)).doesNotThrowAnyException();
        assertThatThrownBy(() -> classAccess.requireManage(teacherP, otherClass))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));
        assertThat(classAccess.canManage(principal(1, Role.SUPER_ADMIN, null, null), otherClass)).isTrue();

        // Lớp đã xóa mềm thì giáo viên mất quyền.
        jdbc.update("UPDATE exam_class SET deleted_at = '2026-01-01T00:00:00Z' WHERE id = ?", clazz);
        assertThat(classAccess.isTeacher(teacher, clazz)).isFalse();
    }

    // ------------------------------------------------------------------ chặn tự thao tác, Super Admin cuối cùng

    @Test
    void khongTuThaoTacLenChinhMinh() {
        UserPrincipal me = principal(10, Role.ADMIN, "0002", 1);
        assertThatThrownBy(() -> guards.checkNotSelf(me, 10))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("SELF_ACTION"));
        assertThatCode(() -> guards.checkNotSelf(me, 11)).doesNotThrowAnyException();
    }

    @Test
    void luonConItNhatMotSuperAdminHoatDong() {
        long sa1 = superAdmin("sa-guard-1");
        assertThatThrownBy(() -> guards.checkSuperAdminRemains(sa1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("LAST_SUPER_ADMIN"));

        long sa2 = superAdmin("sa-guard-2");
        assertThatCode(() -> guards.checkSuperAdminRemains(sa1)).doesNotThrowAnyException();

        jdbc.update("UPDATE app_user SET status = 'INACTIVE' WHERE id = ?", sa2);
        assertThatThrownBy(() -> guards.checkSuperAdminRemains(sa1)).isInstanceOf(ApiException.class);
        // Khóa một Super Admin đã bị khóa, hoặc một người không phải Super Admin, thì không ảnh hưởng gì.
        assertThatCode(() -> guards.checkSuperAdminRemains(sa2)).doesNotThrowAnyException();
        assertThatCode(() -> guards.checkSuperAdminRemains(user("0002", "USER", null))).doesNotThrowAnyException();
    }
}
