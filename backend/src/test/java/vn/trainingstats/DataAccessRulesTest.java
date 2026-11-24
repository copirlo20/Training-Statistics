package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.trainingstats.audit.AuditService;
import vn.trainingstats.settings.AppSettings;

/**
 * Quy tắc truy cập dữ liệu của ứng dụng: ghi chỉ trong giao dịch ghi (pool đọc là chỉ đọc), và lỗi SQLite được dịch
 * sang đúng loại ngoại lệ của Spring.
 */
class DataAccessRulesTest extends DbTestBase {

    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    AuditService audit;
    @Autowired
    AppSettings settings;

    private TransactionTemplate tx;
    private TransactionTemplate readOnlyTx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        readOnlyTx = new TransactionTemplate(txManager);
        readOnlyTx.setReadOnly(true);
    }

    @Test
    void ghiNgoaiGiaoDichBiChanNgay() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO app_setting(key, value) VALUES ('test.ngoai-gd', '1')"))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("Ghi dữ liệu ngoài giao dịch ghi");
    }

    @Test
    void ghiTrongGiaoDichReadOnlyBiChan() {
        assertThatThrownBy(() -> readOnlyTx.executeWithoutResult(s ->
                jdbc.update("INSERT INTO app_setting(key, value) VALUES ('test.readonly', '1')")))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
    }

    @Test
    void ghiTrongGiaoDichGhiThanhCongVaDocLaiDuoc() {
        tx.executeWithoutResult(s ->
                jdbc.update("INSERT OR REPLACE INTO app_setting(key, value) VALUES ('test.trong-gd', '42')"));
        assertThat(settings.getInt("test.trong-gd", 0)).isEqualTo(42);
        Integer inReadOnlyTx = readOnlyTx.execute(s -> settings.getInt("test.trong-gd", 0));
        assertThat(inReadOnlyTx).isEqualTo(42);
    }

    @Test
    void trungKhoaThanhDuplicateKeyException() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO app_setting(key, value) VALUES ('test.trung', '1')");
            jdbc.update("INSERT INTO app_setting(key, value) VALUES ('test.trung', '2')");
        })).isInstanceOf(DuplicateKeyException.class);
        // Giao dịch đã hoàn tác cả dòng đầu.
        assertThat(settings.get("test.trung", null)).isNull();
    }

    @Test
    void viPhamCheckKhoaNgoaiTriggerThanhDataIntegrityViolation() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "INSERT INTO app_user(kind, last_name, first_name, search_text, role) VALUES ('PERSON', 'A', 'B', 'a b', 'USER')")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "INSERT INTO app_user(kind, last_name, first_name, search_text, role, unit_id, personal_code) "
                        + "VALUES ('PERSON', 'A', 'B', 'a b', 'USER', 999999, 'FK-X')")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("DELETE FROM unit WHERE code = '0002'")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void auditLogKhongGhiDuocTrongGiaoDichReadOnly() {
        assertThatThrownBy(() -> readOnlyTx.executeWithoutResult(s ->
                audit.recordAs(AuditService.Actor.SYSTEM, "TEST", null, null, null, null, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cauHinhMacDinhDaSeed() {
        assertThat(settings.passwordMinLength()).isEqualTo(8);
        assertThat(settings.sessionTimeoutMinutes()).isEqualTo(30);
        assertThat(settings.getInt(AppSettings.BACKUP_KEEP, 0)).isEqualTo(5);
        assertThat(settings.get(AppSettings.BACKUP_TIME, null)).isEqualTo("02:00");
        assertThat(settings.get(AppSettings.BACKUP_DIR, null)).isEmpty();
    }
}
