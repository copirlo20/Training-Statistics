package vn.trainingstats.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.trainingstats.security.UserPrincipal;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    public record ChangePasswordRequest(@NotBlank(message = "Nhập mật khẩu hiện tại.") String currentPassword,
                                        @NotBlank(message = "Nhập mật khẩu mới.") String newPassword) {
    }

    private final MeService me;
    private final AuthService auth;

    public MeController(MeService me, AuthService auth) {
        this.me = me;
        this.auth = auth;
    }

    @GetMapping
    public MeService.Me get(@AuthenticationPrincipal UserPrincipal principal) {
        return me.me(principal);
    }

    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal UserPrincipal principal,
                                               @Valid @RequestBody ChangePasswordRequest body) {
        auth.changePassword(principal, body.currentPassword(), body.newPassword());
        return ResponseEntity.noContent().build();
    }
}
