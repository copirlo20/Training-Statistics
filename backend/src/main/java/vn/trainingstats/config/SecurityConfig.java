package vn.trainingstats.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import vn.trainingstats.common.JsonErrorWriter;
import vn.trainingstats.security.AccountRepository;
import vn.trainingstats.security.PrincipalRefreshFilter;

/**
 * Bảo mật web. Mặc định chặn mọi request; chỉ mở các file giao diện tĩnh và hai endpoint đăng nhập, lấy CSRF.
 * Đăng nhập bằng API JSON riêng ({@code /api/v1/auth/login}), phiên giữ phía server.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
public class SecurityConfig {

    /** Content-Security-Policy: chỉ nạp tài nguyên từ chính máy chủ, không script hay style nội tuyến từ nơi khác. */
    static final String CSP = "default-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'self'; "
            + "form-action 'self'; frame-ancestors 'none'";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           SecurityContextRepository contextRepository,
                                           CookieCsrfTokenRepository csrfRepository,
                                           AccountRepository accounts,
                                           JsonErrorWriter errors) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        // Nạp token ở mọi request để cookie luôn có sẵn cho trang giao diện.
        csrfHandler.setCsrfRequestAttributeName(null);

        http
            .securityContext(sc -> sc.securityContextRepository(contextRepository))
            .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository).csrfTokenRequestHandler(csrfHandler))
            // Không lưu request bị chặn vào phiên (tránh tạo phiên cho người chưa đăng nhập).
            .requestCache(rc -> rc.disable())
            .logout(l -> l.disable())
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .headers(h -> h
                .contentSecurityPolicy(c -> c.policyDirectives(CSP))
                .frameOptions(f -> f.deny())
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/login.html", "/favicon.ico",
                        "/css/**", "/js/**", "/assets/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .requestMatchers("/api/v1/audit-logs/**", "/api/v1/admin/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> errors.write(res,
                        HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "Bạn cần đăng nhập."))
                .accessDeniedHandler((req, res, ex) -> {
                    if (ex instanceof CsrfException) {
                        errors.write(res, HttpServletResponse.SC_FORBIDDEN, "CSRF_INVALID",
                                "Phiên làm việc không hợp lệ. Vui lòng tải lại trang.");
                    } else {
                        errors.write(res, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                                "Bạn không có quyền thực hiện thao tác này.");
                    }
                }))
            .addFilterAfter(new PrincipalRefreshFilter(accounts), SecurityContextHolderFilter.class);
        return http.build();
    }
}
