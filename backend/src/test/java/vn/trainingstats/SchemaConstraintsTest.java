package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kiểm tra từng ràng buộc của schema V1 chặn được dữ liệu sai. Mỗi test chạy trong một giao dịch rồi hoàn tác,
 * nên không làm đổi dữ liệu seed mà SeedDataTest đang đối chiếu.
 */
@Transactional
class SchemaConstraintsTest extends DbTestBase {

    private static final String CHECK = "SQLITE_CONSTRAINT_CHECK";
    private static final String UNIQUE = "SQLITE_CONSTRAINT_UNIQUE";
    private static final String FK = "SQLITE_CONSTRAINT_FOREIGNKEY";
    private static final String TRIGGER = "SQLITE_CONSTRAINT_TRIGGER";

    private long counter = 0;

    // ------------------------------------------------------------------ tiện ích

    private long unitId(String code) {
        return jdbc.queryForObject("SELECT id FROM unit WHERE code = ?", Long.class, code);
    }

    private String ma() {
        return "MA" + System.nanoTime() + "-" + (counter++);
    }

    /** Người cá nhân hợp lệ. unitId = null thì tạo không có đơn vị. */
    private long nguoi(String role, Long unitId, String username, String hash, String personalCode) {
        jdbc.update("INSERT INTO app_user(kind, last_name, first_name, search_text, unit_id, role, username, password_hash, personal_code) "
                + "VALUES ('PERSON', 'Nguyen', 'An', 'nguyen an', ?, ?, ?, ?, ?)", unitId, role, username, hash, personalCode);
        return jdbc.queryForObject("SELECT MAX(id) FROM app_user", Long.class);
    }

    private long user() {
        return nguoi("USER", unitId("0002.0001"), null, null, ma());
    }

    private static class Fixture {
        long user, period, clazz, participant, request, result, notification, audit;
    }

    /** Dựng một dòng cho mỗi bảng nghiệp vụ, để thử xóa và sửa. */
    private Fixture fixture() {
        Fixture f = new Fixture();
        f.user = user();
        jdbc.update("INSERT INTO exam_period(name) VALUES ('Dot 1')");
        f.period = jdbc.queryForObject("SELECT MAX(id) FROM exam_period", Long.class);
        jdbc.update("INSERT INTO exam_class(period_id, name) VALUES (?, 'Lop A')", f.period);
        f.clazz = jdbc.queryForObject("SELECT MAX(id) FROM exam_class", Long.class);
        f.participant = themNguoiHoc(f.clazz, f.user);
        jdbc.update("INSERT INTO class_request(class_id, type, user_id, requested_by, requested_unit_id) VALUES (?, 'ADD', ?, ?, ?)",
                f.clazz, user(), f.user, unitId("0002"));
        f.request = jdbc.queryForObject("SELECT MAX(id) FROM class_request", Long.class);
        jdbc.update("INSERT INTO result(participant_id, score_x10, graded_by) VALUES (?, 70, ?)", f.participant, f.user);
        f.result = jdbc.queryForObject("SELECT MAX(id) FROM result", Long.class);
        jdbc.update("INSERT INTO result_history(result_id, old_score_x10, new_score_x10, reason, changed_by) VALUES (?, 70, 75, 'Cham nham', ?)",
                f.result, f.user);
        jdbc.update("INSERT INTO notification(recipient_id, type, title) VALUES (?, 'T', 'Tieu de')", f.user);
        f.notification = jdbc.queryForObject("SELECT MAX(id) FROM notification", Long.class);
        jdbc.update("INSERT INTO audit_log(action) VALUES ('LOGIN')");
        f.audit = jdbc.queryForObject("SELECT MAX(id) FROM audit_log", Long.class);
        return f;
    }

    private long themNguoiHoc(long classId, long userId) {
        jdbc.update("INSERT INTO class_participant(class_id, user_id, unit_id_snapshot) VALUES (?, ?, ?)",
                classId, userId, unitId("0002.0001"));
        return jdbc.queryForObject("SELECT MAX(id) FROM class_participant", Long.class);
    }

    /** Trigger BEFORE INSERT chạy trước khi SQLite kiểm tra CHECK, nên với dữ liệu vi phạm cả hai, chấp nhận mã nào cũng được. */
    private void assertBiChan(Runnable thaoTac) {
        assertThatThrownBy(thaoTac::run).satisfies(e -> assertThat(loiSqlite(e)).startsWith("SQLITE_CONSTRAINT"));
    }

    private void assertLoi(String kyVong, Runnable thaoTac) {
        assertThatThrownBy(thaoTac::run).satisfies(e -> assertThat(loiSqlite(e)).isEqualTo(kyVong));
    }

    // ------------------------------------------------------------------ app_user

    @Test
    void nguoiCaNhanBatBuocHoTenVaMaDinhDanh() {
        long unit = unitId("0002");
        assertLoi(CHECK, () -> jdbc.update("INSERT INTO app_user(kind, last_name, first_name, search_text, unit_id, role) "
                + "VALUES ('PERSON', 'Nguyen', 'An', 'nguyen an', ?, 'USER')", unit));       // thiếu mã định danh
        assertLoi(CHECK, () -> jdbc.update("INSERT INTO app_user(kind, first_name, search_text, unit_id, role, personal_code) "
                + "VALUES ('PERSON', 'An', 'an', ?, 'USER', 'X1')", unit));                   // thiếu họ đệm
        assertLoi(CHECK, () -> jdbc.update("INSERT INTO app_user(kind, last_name, search_text, unit_id, role, personal_code) "
                + "VALUES ('PERSON', 'Nguyen', 'nguyen', ?, 'USER', 'X2')", unit));           // thiếu tên
    }

    @Test
    void superAdminKhongCoDonViVaNguoiKhacBatBuocCoDonVi() {
        assertLoi(CHECK, () -> nguoi("SUPER_ADMIN", unitId("0002"), "root", "hash", ma()));
        assertLoi(CHECK, () -> nguoi("USER", null, null, null, ma()));
        assertLoi(CHECK, () -> nguoi("ADMIN", null, "adm", "hash", ma()));
        assertThatCode(() -> nguoi("SUPER_ADMIN", null, "root", "hash", ma())).doesNotThrowAnyException();
    }

    @Test
    void adminBatBuocTaiKhoanVaMatKhau() {
        long unit = unitId("0002");
        assertLoi(CHECK, () -> nguoi("ADMIN", unit, null, null, ma()));
        assertLoi(CHECK, () -> nguoi("ADMIN", unit, "adm1", null, ma()));
        assertLoi(CHECK, () -> nguoi("USER", unit, null, "hash-khong-co-tai-khoan", ma()));
        assertThatCode(() -> nguoi("ADMIN", unit, "adm2", "hash", ma())).doesNotThrowAnyException();
        assertThatCode(() -> nguoi("USER", unit, null, null, ma())).doesNotThrowAnyException();
    }

    @Test
    void giaTriKhongHopLeBiChan() {
        long unit = unitId("0002");
        assertLoi(CHECK, () -> jdbc.update("INSERT INTO app_user(kind, last_name, first_name, search_text, unit_id, role, personal_code) "
                + "VALUES ('KHAC', 'A', 'B', 'a b', ?, 'USER', 'K1')", unit));
        assertLoi(CHECK, () -> jdbc.update("INSERT INTO app_user(kind, last_name, first_name, search_text, unit_id, role, personal_code) "
                + "VALUES ('PERSON', 'A', 'B', 'a b', ?, 'ROOT', 'K2')", unit));
        assertLoi(CHECK, () -> jdbc.update("INSERT INTO app_user(kind, last_name, first_name, search_text, unit_id, role, status, personal_code) "
                + "VALUES ('PERSON', 'A', 'B', 'a b', ?, 'USER', 'GONE', 'K3')", unit));
    }

    @Test
    void nguoiDungLoaiDonViKhongCoTaiKhoanVaMoiDonViMotBanGhi() {
        long unit = unitId("0002.0001");
        assertLoi(CHECK, () -> jdbc.update("INSERT INTO app_user(kind, search_text, unit_id, role, username, password_hash) "
                + "VALUES ('UNIT', 'ban quan huan', ?, 'USER', 'banquanhuan', 'hash')", unit));
        assertLoi(CHECK, () -> jdbc.update("INSERT INTO app_user(kind, search_text, unit_id, role, personal_code) "
                + "VALUES ('UNIT', 'ban quan huan', ?, 'USER', 'MA-GIA')", unit));
        assertLoi(CHECK, () -> jdbc.update("INSERT INTO app_user(kind, search_text, unit_id, role) VALUES ('UNIT', 'x', ?, 'ADMIN')", unit));
        assertLoi(CHECK, () -> jdbc.update("INSERT INTO app_user(kind, search_text, unit_id, role, last_name) "
                + "VALUES ('UNIT', 'x', ?, 'USER', 'Ho')", unit));
        assertLoi(CHECK, () -> jdbc.update("INSERT INTO app_user(kind, search_text, role) VALUES ('UNIT', 'x', 'USER')"));   // không có đơn vị

        jdbc.update("INSERT INTO app_user(kind, search_text, unit_id, role) VALUES ('UNIT', 'ban quan huan', ?, 'USER')", unit);
        assertLoi(UNIQUE, () -> jdbc.update("INSERT INTO app_user(kind, search_text, unit_id, role) VALUES ('UNIT', 'ban quan huan', ?, 'USER')", unit));
    }

    @Test
    void maDinhDanhVaTaiKhoanKhongTrungKeCaKhacHoaThuong() {
        long unit = unitId("0002");
        nguoi("ADMIN", unit, "QuanTri", "hash", "MA-DUY-NHAT");
        assertLoi(UNIQUE, () -> nguoi("USER", unit, null, null, "MA-DUY-NHAT"));
        assertLoi(UNIQUE, () -> nguoi("ADMIN", unit, "quantri", "hash", ma()));
        assertThatCode(() -> {
            nguoi("USER", unit, null, null, ma());      // nhiều người không có tài khoản vẫn hợp lệ
            nguoi("USER", unit, null, null, ma());
        }).doesNotThrowAnyException();
    }

    @Test
    void trangThaiDeletedPhaiCoThoiDiemXoa() {
        long id = user();
        assertLoi(CHECK, () -> jdbc.update("UPDATE app_user SET status = 'DELETED' WHERE id = ?", id));
        assertLoi(CHECK, () -> jdbc.update("UPDATE app_user SET deleted_at = '2026-01-01T00:00:00Z' WHERE id = ?", id));
        assertThatCode(() -> jdbc.update("UPDATE app_user SET status = 'DELETED', deleted_at = '2026-01-01T00:00:00Z' WHERE id = ?", id))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ unit

    @Test
    void maDonViDungDinhDangVaDungQuyTacCha() {
        assertLoi(CHECK, () -> jdbc.update("INSERT INTO unit(code, name, level) VALUES ('12', 'Sai do dai', 1)"));
        assertLoi(CHECK, () -> jdbc.update("INSERT INTO unit(code, name, level) VALUES ('9999.0001', 'Cap 1 co dau cham', 1)"));
        long parent = unitId("0002");
        assertBiChan(() -> jdbc.update("INSERT INTO unit(code, name, level) VALUES ('0002.0099', 'Cap 2 khong cha', 2)"));
        assertLoi(CHECK, () -> jdbc.update("INSERT INTO unit(code, name, parent_id, level) VALUES ('9998', 'Cap 1 co cha', ?, 1)", parent));
        // cấp 2 nhưng mã không bắt đầu bằng mã cha
        assertLoi(TRIGGER, () -> jdbc.update("INSERT INTO unit(code, name, parent_id, level) VALUES ('0003.0099', 'Sai ma cha', ?, 2)", parent));
        // cấp 2 dưới một đơn vị cấp 2
        long cap2 = unitId("0002.0001");
        assertBiChan(() -> jdbc.update("INSERT INTO unit(code, name, parent_id, level) VALUES ('0002.0001.0001', 'Cap 3', ?, 2)", cap2));
        assertThatCode(() -> jdbc.update("INSERT INTO unit(code, name, parent_id, level) VALUES ('0002.0099', 'Hop le', ?, 2)", parent))
                .doesNotThrowAnyException();
    }

    @Test
    void maCapVaDonViChaKhongDoiDuocNhungTenDoiDuoc() {
        assertThatThrownBy(() -> jdbc.update("UPDATE unit SET code = '7777' WHERE code = '0002'"))
                .satisfies(e -> assertThat(thongBaoGoc(e)).contains("Khong doi duoc ma"));
        assertThatThrownBy(() -> jdbc.update("UPDATE unit SET parent_id = (SELECT id FROM unit WHERE code = '0003') WHERE code = '0002.0001'"))
                .satisfies(e -> assertThat(thongBaoGoc(e)).contains("Khong doi duoc ma"));
        assertThatCode(() -> jdbc.update("UPDATE unit SET name = 'Phong Tham muu (doi ten)', active = 0 WHERE code = '0002'"))
                .doesNotThrowAnyException();
    }

    @Test
    void khongConBangMonKiemTra() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE '%subject%'", Integer.class))
                .as("trường Môn kiểm tra đã bỏ khỏi schema").isZero();
        assertThat(jdbc.queryForList("SELECT name FROM pragma_table_info('exam_class')", String.class))
                .doesNotContain("subject_id").contains("period_id", "name", "exam_date");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class))
                .as("chỉ một migration gộp V1").isEqualTo(1);
    }

    // ------------------------------------------------------------------ xóa cứng và bảng chỉ thêm

    @Test
    void khongXoaCungDuocBatKyBangNghiepVuNao() {
        fixture();
        for (String bang : List.of("unit", "rank", "position", "specialty", "absence_reason", "app_user",
                "exam_period", "exam_class", "class_request", "class_participant", "result", "result_history",
                "notification", "audit_log")) {
            assertThatThrownBy(() -> jdbc.update("DELETE FROM " + bang))
                    .as("DELETE trên %s", bang)
                    .satisfies(e -> assertThat(thongBaoGoc(e)).containsIgnoringCase("khong duoc xoa"));
        }
    }

    @Test
    void nhatKyVaLichSuSuaDiemChiDuocThem() {
        fixture();
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET action = 'SUA'"))
                .satisfies(e -> assertThat(thongBaoGoc(e)).contains("Nhat ky chi duoc them"));
        assertThatThrownBy(() -> jdbc.update("UPDATE result_history SET reason = 'sua lich su'"))
                .satisfies(e -> assertThat(thongBaoGoc(e)).contains("chi duoc them"));
        assertThatCode(() -> jdbc.update("INSERT INTO audit_log(action, actor_username) VALUES ('LOGIN_FAILED', 'khong-ton-tai')"))
                .doesNotThrowAnyException();
    }

    @Test
    void banGanGiaoVienVaChuyenNganhChoPhepXoaDeGanLai() {
        Fixture f = fixture();
        jdbc.update("INSERT INTO class_teacher(class_id, user_id) VALUES (?, ?)", f.clazz, f.user);
        assertThat(jdbc.update("DELETE FROM class_teacher WHERE class_id = ?", f.clazz)).isEqualTo(1);
        jdbc.update("INSERT INTO user_specialty(user_id, specialty_id) VALUES (?, (SELECT id FROM specialty LIMIT 1))", f.user);
        assertThat(jdbc.update("DELETE FROM user_specialty WHERE user_id = ?", f.user)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ điểm

    @Test
    void diemNgoaiKhoangVaXepLoaiOCacDiemBien() {
        Fixture f = fixture();
        long[][] cases = {{0, 0}, {49, 0}, {50, 1}, {65, 1}, {66, 2}, {79, 2}, {80, 3}, {100, 3}};
        String[] ten = {"FAIL", "PASS", "GOOD", "EXCELLENT"};
        for (long[] c : cases) {
            long p = themNguoiHoc(f.clazz, user());
            jdbc.update("INSERT INTO result(participant_id, score_x10, graded_by) VALUES (?, ?, ?)", p, c[0], f.user);
            String grade = jdbc.queryForObject("SELECT grade FROM result WHERE participant_id = ?", String.class, p);
            assertThat(grade).as("điểm %.1f", c[0] / 10.0).isEqualTo(ten[(int) c[1]]);
        }
        long p = themNguoiHoc(f.clazz, user());
        assertLoi(CHECK, () -> jdbc.update("INSERT INTO result(participant_id, score_x10, graded_by) VALUES (?, 101, ?)", p, f.user));
        assertLoi(CHECK, () -> jdbc.update("INSERT INTO result(participant_id, score_x10, graded_by) VALUES (?, -1, ?)", p, f.user));
    }

    @Test
    void moiNguoiHocChiMotDiem() {
        Fixture f = fixture();   // participant đã có điểm 70
        assertLoi(UNIQUE, () -> jdbc.update("INSERT INTO result(participant_id, score_x10, graded_by) VALUES (?, 90, ?)", f.participant, f.user));
    }

    @Test
    void lichSuSuaDiemBatBuocLyDo() {
        Fixture f = fixture();
        assertLoi(CHECK, () -> jdbc.update("INSERT INTO result_history(result_id, old_score_x10, new_score_x10, reason, changed_by) "
                + "VALUES (?, 70, 80, '   ', ?)", f.result, f.user));
    }

    // ------------------------------------------------------------------ người học và đề nghị

    @Test
    void motNguoiMotLanTrongLopNhungThemLaiDuocSauKhiGo() {
        Fixture f = fixture();
        assertLoi(UNIQUE, () -> themNguoiHoc(f.clazz, f.user));
        jdbc.update("UPDATE class_participant SET removed_at = '2026-01-01T00:00:00Z', removed_by = ?, removed_reason = 'Them nham' WHERE id = ?",
                f.user, f.participant);
        assertThatCode(() -> themNguoiHoc(f.clazz, f.user)).doesNotThrowAnyException();
    }

    @Test
    void thongTinGoNguoiHocPhaiDayDu() {
        Fixture f = fixture();
        assertLoi(CHECK, () -> jdbc.update("UPDATE class_participant SET removed_at = '2026-01-01T00:00:00Z' WHERE id = ?", f.participant));
        assertLoi(CHECK, () -> jdbc.update("UPDATE class_participant SET removed_reason = 'khong co thoi diem' WHERE id = ?", f.participant));
        assertLoi(CHECK, () -> jdbc.update("UPDATE class_participant SET added_via = 'REQUEST' WHERE id = ?", f.participant));   // thiếu request_id
    }

    @Test
    void khoaNgoaiChanThamChieuKhongTonTai() {
        Fixture f = fixture();
        assertLoi(FK, () -> jdbc.update("INSERT INTO class_participant(class_id, user_id, unit_id_snapshot) VALUES (?, 999999, ?)", f.clazz, unitId("0002")));
        assertLoi(FK, () -> jdbc.update("INSERT INTO class_participant(class_id, user_id, unit_id_snapshot) VALUES (999999, ?, ?)", f.user, unitId("0002")));
        assertLoi(FK, () -> jdbc.update("INSERT INTO exam_class(period_id, name) VALUES (999999, 'Khong co dot')"));
        assertLoi(FK, () -> jdbc.update("INSERT INTO app_user(kind, last_name, first_name, search_text, unit_id, role, personal_code) "
                + "VALUES ('PERSON', 'A', 'B', 'a b', 999999, 'USER', 'FK1')"));
    }

    @Test
    void khongHaiDeNghiDangChoGiongHetNhau() {
        Fixture f = fixture();
        long nguoiDuocDeNghi = user();
        jdbc.update("INSERT INTO class_request(class_id, type, user_id, requested_by, requested_unit_id) VALUES (?, 'ADD', ?, ?, ?)",
                f.clazz, nguoiDuocDeNghi, f.user, unitId("0002"));
        assertLoi(UNIQUE, () -> jdbc.update("INSERT INTO class_request(class_id, type, user_id, requested_by, requested_unit_id) VALUES (?, 'ADD', ?, ?, ?)",
                f.clazz, nguoiDuocDeNghi, f.user, unitId("0002")));
        // đề nghị đã xử lý xong thì được đề nghị lại
        jdbc.update("UPDATE class_request SET status = 'REJECTED', decided_by = ?, decided_at = '2026-01-01T00:00:00Z' "
                + "WHERE class_id = ? AND user_id = ?", f.user, f.clazz, nguoiDuocDeNghi);
        assertThatCode(() -> jdbc.update("INSERT INTO class_request(class_id, type, user_id, requested_by, requested_unit_id) VALUES (?, 'ADD', ?, ?, ?)",
                f.clazz, nguoiDuocDeNghi, f.user, unitId("0002"))).doesNotThrowAnyException();
    }

    @Test
    void trangThaiDeNghiPhaiKhopThoiDiemXuLy() {
        Fixture f = fixture();
        assertLoi(CHECK, () -> jdbc.update("UPDATE class_request SET status = 'APPROVED' WHERE id = ?", f.request));
        assertLoi(CHECK, () -> jdbc.update("UPDATE class_request SET decided_at = '2026-01-01T00:00:00Z' WHERE id = ?", f.request));
        assertLoi(CHECK, () -> jdbc.update("UPDATE class_request SET status = 'XONG' WHERE id = ?", f.request));
    }

    @Test
    void ngayKetThucDotKhongTruocNgayBatDau() {
        assertLoi(CHECK, () -> jdbc.update("INSERT INTO exam_period(name, start_date, end_date) VALUES ('Sai ngay', '2026-12-31', '2026-01-01')"));
        assertThatCode(() -> jdbc.update("INSERT INTO exam_period(name, start_date, end_date) VALUES ('Dung ngay', '2026-01-01', '2026-12-31')"))
                .doesNotThrowAnyException();
    }
}
