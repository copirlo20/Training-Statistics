package vn.trainingstats.admin;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.trainingstats.audit.AuditAction;
import vn.trainingstats.audit.AuditService;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.Times;
import vn.trainingstats.common.VietnameseText;
import vn.trainingstats.security.PasswordPolicy;

/**
 * Tạo Super Admin ban đầu và đặt lại mật khẩu Super Admin. Chỉ được gọi từ máy chủ (khởi tạo lần đầu, công cụ dòng
 * lệnh), không có API web nào gọi tới đây.
 */
@Service
public class SuperAdminService {

    public static final String DEFAULT_USERNAME = "admin";
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,50}");

    public enum ResetOutcome { CREATED, RESET }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SuperAdminService(JdbcTemplate jdbc, PasswordEncoder encoder, PasswordPolicy policy, AuditService audit,
                             PlatformTransactionManager txManager, Clock clock) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.policy = policy;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    public boolean anySuperAdmin() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE role = 'SUPER_ADMIN' AND status <> 'DELETED'", Integer.class);
        return n != null && n > 0;
    }

    /** Tạo Super Admin đầu tiên. Từ chối nếu hệ thống đã có Super Admin. */
    public long createInitial(String username, String rawPassword) {
        checkUsername(username);
        policy.validate("password", rawPassword);
        String hash = encoder.encode(rawPassword);
        return tx.execute(status -> {
            if (anySuperAdmin()) {
                throw ApiException.conflict("SUPER_ADMIN_EXISTS", "Hệ thống đã có Super Admin.");
            }
            long newId = insert(username, hash);
            audit.recordAs(AuditService.Actor.SYSTEM, AuditAction.SUPER_ADMIN_CREATED, "app_user", newId, null,
                    null, Map.of("username", username, "source", "first-run"));
            return newId;
        });
    }

    /**
     * Đặt lại mật khẩu cho Super Admin có tên {@code username}, đồng thời mở khóa nếu đang bị khóa hoặc xóa mềm.
     * Nếu chưa có tài khoản tên đó thì tạo Super Admin mới. Từ chối nếu tên đó thuộc một tài khoản không phải
     * Super Admin (công cụ này không dùng để nâng quyền tài khoản khác).
     */
    public ResetOutcome reset(String username, String rawPassword) {
        checkUsername(username);
        policy.validate("password", rawPassword);
        String hash = encoder.encode(rawPassword);
        return tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, role FROM app_user WHERE username = ?", username);
            String now = Times.now(clock);
            if (rows.isEmpty()) {
                long id = insert(username, hash);
                audit.recordAs(AuditService.Actor.SYSTEM, AuditAction.SUPER_ADMIN_CREATED, "app_user", id, null,
                        null, Map.of("username", username, "source", "reset-superadmin"));
                return ResetOutcome.CREATED;
            }
            long id = ((Number) rows.get(0).get("id")).longValue();
            if (!"SUPER_ADMIN".equals(rows.get(0).get("role"))) {
                throw ApiException.conflict("NOT_SUPER_ADMIN",
                        "Tai khoan '" + username + "' ton tai nhung khong phai Super Admin.");
            }
            jdbc.update("""
                    UPDATE app_user
                    SET password_hash = ?, must_change_password = 0, status = 'ACTIVE', deleted_at = NULL, deleted_by = NULL,
                        updated_at = ?, updated_by = NULL, version = version + 1
                    WHERE id = ?
                    """, hash, now, id);
            audit.recordAs(AuditService.Actor.SYSTEM, AuditAction.SUPER_ADMIN_RESET, "app_user", id, null, null,
                    Map.of("username", username, "source", "reset-superadmin"));
            return ResetOutcome.RESET;
        });
    }

    private long insert(String username, String hash) {
        String lastName = "Quản trị";
        String firstName = "hệ thống";
        jdbc.update("""
                INSERT INTO app_user (kind, last_name, first_name, search_text, role, status, username, password_hash,
                                      personal_code)
                VALUES ('PERSON', ?, ?, ?, 'SUPER_ADMIN', 'ACTIVE', ?, ?, ?)
                """, lastName, firstName, VietnameseText.searchKey(lastName, firstName, username), username, hash,
                "SA-" + username.toUpperCase(Locale.ROOT));
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
    }

    private static void checkUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw ApiException.invalidField("username",
                    "Ten tai khoan chi gom chu, so, dau cham, gach duoi, gach ngang, dai 3-50 ky tu.");
        }
    }
}
