package vn.trainingstats.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * Bean bảo mật dùng chung, tách khỏi {@link SecurityConfig} để chế độ dòng lệnh (--reset-superadmin, không có web)
 * vẫn khởi động được.
 */
@Configuration
public class SecurityBeans {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * Token CSRF nằm trong cookie XSRF-TOKEN đọc được bằng JavaScript; mỗi request ghi phải gửi lại trong header
     * X-XSRF-TOKEN. Cookie không đặt Secure vì hệ thống chạy HTTP.
     */
    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setCookieCustomizer(c -> c.sameSite("Lax").secure(false).path("/"));
        return repo;
    }
}
