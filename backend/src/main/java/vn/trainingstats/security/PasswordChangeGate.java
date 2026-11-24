package vn.trainingstats.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import vn.trainingstats.common.ApiException;

/**
 * Tài khoản có mật khẩu do người khác đặt (cờ {@code must_change_password}) chỉ được xem thông tin của mình, đổi mật
 * khẩu và đăng xuất; mọi API khác nhận 403 {@code PASSWORD_CHANGE_REQUIRED}. Chặn ở máy chủ vì giao diện chỉ chuyển
 * trang, không phải là kiểm soát truy cập. Cờ đọc từ principal nạp lại ở mỗi request, nên đổi xong có hiệu lực ngay.
 */
public class PasswordChangeGate implements HandlerInterceptor {

    public static final String CODE = "PASSWORD_CHANGE_REQUIRED";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p && p.mustChangePassword()
                && !allowed(request.getMethod(), request.getRequestURI().substring(request.getContextPath().length()))) {
            throw ApiException.forbidden(CODE, "Bạn cần đổi mật khẩu trước khi tiếp tục sử dụng.");
        }
        return true;
    }

    static boolean allowed(String method, String path) {
        return path.startsWith("/api/v1/auth/")
                || ("GET".equals(method) && "/api/v1/me".equals(path))
                || ("PUT".equals(method) && "/api/v1/me/password".equals(path));
    }
}
