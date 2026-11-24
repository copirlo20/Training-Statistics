package vn.trainingstats.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Nạp lại người dùng từ DB ở mỗi request có phiên đăng nhập. Nếu tài khoản đã bị khóa, xóa hoặc mất tài khoản thì
 * hủy phiên ngay (request chạy tiếp như người chưa đăng nhập và nhận 401). Nếu vai trò hay đơn vị đã đổi thì dùng
 * giá trị mới, nên phạm vi dữ liệu không bao giờ dựa trên thông tin cũ trong phiên.
 *
 * Không đăng ký thành bean để Spring Boot không tự gắn nó vào chuỗi filter của servlet lần thứ hai.
 */
public class PrincipalRefreshFilter extends OncePerRequestFilter {

    private final AccountRepository accounts;

    public PrincipalRefreshFilter(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal old) {
            Optional<UserPrincipal> fresh = accounts.loadActivePrincipal(old.userId());
            if (fresh.isEmpty()) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
            } else if (!fresh.get().equals(old)) {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        fresh.get(), null, fresh.get().authorities()));
                SecurityContextHolder.setContext(context);
            }
        }
        chain.doFilter(request, response);
    }
}
