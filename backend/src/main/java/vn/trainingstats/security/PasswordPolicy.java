package vn.trainingstats.security;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.settings.AppSettings;

/**
 * Chính sách mật khẩu tối thiểu đã chốt (PRD #17, #26): chỉ kiểm tra độ dài. Giới hạn trên 72 byte là của BCrypt,
 * dài hơn sẽ bị cắt ngầm nên phải chặn.
 */
@Component
public class PasswordPolicy {

    public static final int MAX_BYTES = 72;

    private final AppSettings settings;

    public PasswordPolicy(AppSettings settings) {
        this.settings = settings;
    }

    public void validate(String field, String password) {
        int min = settings.passwordMinLength();
        if (password == null || password.isBlank() || password.length() < min) {
            throw ApiException.invalidField(field, "Mật khẩu phải có ít nhất " + min + " ký tự.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw ApiException.invalidField(field, "Mật khẩu quá dài (tối đa " + MAX_BYTES + " byte).");
        }
    }
}
