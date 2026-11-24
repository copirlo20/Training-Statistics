package vn.trainingstats.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.trainingstats.security.UserPrincipal;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record LoginRequest(@NotBlank(message = "Nhập tài khoản.") String username,
                               @NotBlank(message = "Nhập mật khẩu.") String password) {
    }

    private final AuthService auth;
    private final MeService me;

    public AuthController(AuthService auth, MeService me) {
        this.auth = auth;
        this.me = me;
    }

    /** Trả token CSRF hiện tại (cookie XSRF-TOKEN cũng được đặt), để giao diện gửi kèm header ở request ghi. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/login")
    public MeService.Me login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                              HttpServletResponse response) {
        UserPrincipal principal = auth.login(body.username(), body.password(), request, response);
        return me.me(principal);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal UserPrincipal principal,
                                       HttpServletRequest request, HttpServletResponse response) {
        auth.logout(principal, request, response);
        return ResponseEntity.noContent().build();
    }
}
