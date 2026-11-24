package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpStatus;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.OptimisticLock;
import vn.trainingstats.common.PageQuery;
import vn.trainingstats.common.SqliteExceptionTranslator;
import vn.trainingstats.common.Times;
import vn.trainingstats.common.VietnameseText;

/** Test đơn vị cho các tiện ích dùng chung, không cần Spring hay DB. */
class CommonUtilsTest {

    @Test
    void boDauTiengViet() {
        assertThat(VietnameseText.searchKey("Nguyễn Trọng Đạt")).isEqualTo("nguyen trong dat");
        assertThat(VietnameseText.searchKey("ĐẶNG  Thị   Ánh ")).isEqualTo("dang thi anh");
        assertThat(VietnameseText.searchKey("Phòng Hậu cần - Kỹ thuật")).isEqualTo("phong hau can - ky thuat");
        assertThat(VietnameseText.searchKey("Thượng úy QNCN")).isEqualTo("thuong uy qncn");
        assertThat(VietnameseText.searchKey("ăâêôơưđ ẮẰẲẴẶ")).isEqualTo("aaeooud aaaaa");
        assertThat(VietnameseText.searchKey((String) null)).isEmpty();
        assertThat(VietnameseText.searchKey("Trần", null, "  ", "Văn B")).isEqualTo("tran van b");
    }

    @Test
    void congCuDongLenhInThongBaoKhongDau() {
        assertThat(TrainingStatsApplication.withoutMarks("Mật khẩu phải có ít nhất 8 ký tự. Đơn vị"))
                .isEqualTo("Mat khau phai co it nhat 8 ky tu. Don vi");
    }

    @Test
    void mauLikeThoatKyTuDacBiet() {
        assertThat(VietnameseText.likeContains("Đạt")).isEqualTo("%dat%");
        assertThat(VietnameseText.likeContains("50%_a\\b")).isEqualTo("%50\\%\\_a\\\\b%");
    }

    @Test
    void thoiGianTheoGioVietNam() {
        assertThat(Times.format(Instant.parse("2026-11-18T03:15:00Z"))).isEqualTo("2026-11-18T03:15:00.000Z");
        // 00:00 ngày 18/11 giờ Việt Nam là 17:00 ngày 17/11 UTC.
        assertThat(Times.startOfDay(LocalDate.of(2026, 11, 18))).isEqualTo("2026-11-17T17:00:00.000Z");
        assertThat(Times.startOfNextDay(LocalDate.of(2026, 11, 18))).isEqualTo("2026-11-18T17:00:00.000Z");
    }

    @Test
    void phanTrangKiemTraGioiHan() {
        assertThat(PageQuery.of(null, null)).isEqualTo(new PageQuery(0, PageQuery.DEFAULT_SIZE));
        assertThat(PageQuery.of(2, 10).offset()).isEqualTo(20);
        assertThatThrownBy(() -> PageQuery.of(-1, 10)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PageQuery.of(0, 0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PageQuery.of(0, PageQuery.MAX_SIZE + 1)).isInstanceOf(ApiException.class);
    }

    @Test
    void khoaLacQuanTra409KhiKhongCapNhatDuoc() {
        OptimisticLock.check(1);
        assertThatThrownBy(() -> OptimisticLock.check(0))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
                });
    }

    @Test
    void dichMaLoiSqlite() {
        SqliteExceptionTranslator t = new SqliteExceptionTranslator();
        assertThat(t.translate("t", "s", sqlite(SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(t.translate("t", "s", sqlite(SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY)))
                .isInstanceOf(DuplicateKeyException.class);
        for (SQLiteErrorCode code : new SQLiteErrorCode[] {SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY,
                SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, SQLiteErrorCode.SQLITE_CONSTRAINT_NOTNULL,
                SQLiteErrorCode.SQLITE_CONSTRAINT_TRIGGER}) {
            assertThat(t.translate("t", "s", sqlite(code)))
                    .as(code.name())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .isNotInstanceOf(DuplicateKeyException.class);
        }
        assertThat(t.translate("t", "s", sqlite(SQLiteErrorCode.SQLITE_BUSY)))
                .isInstanceOf(CannotAcquireLockException.class);
        assertThat(t.translate("t", "s", sqlite(SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT)))
                .isInstanceOf(CannotAcquireLockException.class);
        assertThat(t.translate("t", "s", sqlite(SQLiteErrorCode.SQLITE_READONLY)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
    }

    private static SQLException sqlite(SQLiteErrorCode code) {
        return new SQLiteException("test " + code.name(), code);
    }
}
