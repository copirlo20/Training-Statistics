package vn.trainingstats.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.trainingstats.audit.AuditAction;
import vn.trainingstats.audit.AuditService;
import vn.trainingstats.audit.AuditService.Actor;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.OptimisticLock;
import vn.trainingstats.common.Times;
import vn.trainingstats.security.AccountRepository;
import vn.trainingstats.security.AccountRepository.LoginAccount;
import vn.trainingstats.security.PasswordPolicy;
import vn.trainingstats.security.UserPrincipal;
import vn.trainingstats.settings.AppSettings;

/**
 * Đăng nhập, đăng xuất, đổi mật khẩu. BCrypt chạy NGOÀI giao dịch (chậm có chủ đích), chỉ phần ghi DB nằm trong
 * giao dịch ngắn, để không giữ khóa ghi của SQLite trong lúc băm.
 *
 * Đã chốt không khóa và không trì hoãn khi nhập sai (PRD #26): chỉ ghi audit log mọi lần thất bại.
 */
@Service
public class AuthService {

    static final String INVALID_MESSAGE = "Tài khoản hoặc mật khẩu không đúng.";

    private final AccountRepository accounts;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final SecurityContextRepository contextRepository;
    private final CookieCsrfTokenRepository csrfRepository;
    private final AuditService audit;
    private final AppSettings settings;
    private final TransactionTemplate tx;
    private final Clock clock;
    /** Băm giả để thời gian phản hồi như nhau dù tài khoản có tồn tại hay không. */
    private final String dummyHash;

    public AuthService(AccountRepository accounts, PasswordEncoder encoder, PasswordPolicy policy,
                       SecurityContextRepository contextRepository, CookieCsrfTokenRepository csrfRepository,
                       AuditService audit, AppSettings settings, PlatformTransactionManager txManager, Clock clock) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.policy = policy;
        this.contextRepository = contextRepository;
        this.csrfRepository = csrfRepository;
        this.audit = audit;
        this.settings = settings;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public UserPrincipal login(String username, String password, HttpServletRequest request,
                               HttpServletResponse response) {
        String name = username == null ? "" : username.trim();
        Optional<LoginAccount> found = name.isEmpty() ? Optional.empty() : accounts.findLogin(name);
        Long accountId = found.map(LoginAccount::id).orElse(null);

        boolean usable = found.isPresent()
                && found.get().passwordHash() != null
                && "PERSON".equals(found.get().kind())
                && !"DELETED".equals(found.get().status());
        boolean match = encoder.matches(password == null ? "" : password,
                usable ? found.get().passwordHash() : dummyHash);

        if (!usable || !match) {
            failed(name, accountId, "INVALID_CREDENTIALS");
            throw ApiException.unauthorized("INVALID_CREDENTIALS", INVALID_MESSAGE);
        }
        // Chỉ báo "bị khóa" khi mật khẩu đã đúng, nên người không biết mật khẩu không dò được tài khoản nào bị khóa.
        if ("INACTIVE".equals(found.get().status())) {
            failed(name, accountId, "ACCOUNT_LOCKED");
            throw ApiException.unauthorized("ACCOUNT_LOCKED", "Tài khoản đã bị khóa. Liên hệ quản trị viên.");
        }
        UserPrincipal principal = accounts.loadActivePrincipal(accountId).orElseThrow(() -> {
            failed(name, accountId, "NOT_ACTIVE");
            return ApiException.unauthorized("INVALID_CREDENTIALS", INVALID_MESSAGE);
        });

        // Đổi mã phiên sau khi đăng nhập (chống cố định phiên), rồi đặt thời gian hết phiên theo cấu hình.
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            request.changeSessionId();
        }
        HttpSession session = request.getSession(true);
        session.setMaxInactiveInterval(settings.sessionTimeoutMinutes() * 60);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.authorities()));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        // Token CSRF mới cho phiên mới.
        CsrfToken token = csrfRepository.generateToken(request);
        csrfRepository.saveToken(token, request, response);

        tx.executeWithoutResult(status -> {
            accounts.updateLastLogin(principal.userId(), Times.now(clock));
            audit.recordAs(new Actor(principal.userId(), principal.username()), AuditAction.LOGIN_SUCCESS,
                    "app_user", principal.userId(), principal.unitCode(), null, null);
        });
        return principal;
    }

    private void failed(String attemptedUsername, Long accountId, String reason) {
        audit.recordAs(new Actor(null, attemptedUsername), AuditAction.LOGIN_FAILED, "app_user", accountId, null,
                null, Map.of("reason", reason));
    }

    public void logout(UserPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        if (principal != null) {
            audit.recordAs(new Actor(principal.userId(), principal.username()), AuditAction.LOGOUT, "app_user",
                    principal.userId(), principal.unitCode(), null, null);
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        csrfRepository.saveToken(null, request, response);
    }

    public void changePassword(UserPrincipal principal, String currentPassword, String newPassword) {
        String hash = accounts.findPasswordHash(principal.userId())
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Bạn cần đăng nhập."));
        if (currentPassword == null || !encoder.matches(currentPassword, hash)) {
            throw ApiException.invalidField("currentPassword", "Mật khẩu hiện tại không đúng.");
        }
        policy.validate("newPassword", newPassword);
        if (encoder.matches(newPassword, hash)) {
            throw ApiException.invalidField("newPassword", "Mật khẩu mới phải khác mật khẩu hiện tại.");
        }
        String newHash = encoder.encode(newPassword);
        tx.executeWithoutResult(status -> {
            OptimisticLock.check(accounts.updatePasswordHash(principal.userId(), hash, newHash, Times.now(clock)));
            audit.recordAs(new Actor(principal.userId(), principal.username()), AuditAction.PASSWORD_CHANGED,
                    "app_user", principal.userId(), principal.unitCode(), null, null);
        });
    }
}
