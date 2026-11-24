package vn.trainingstats.security;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Người đang đăng nhập. Được nạp lại từ DB ở mỗi request ({@link PrincipalRefreshFilter}), nên vai trò và đơn vị
 * luôn là giá trị hiện tại: bị khóa, đổi quyền hay chuyển đơn vị có hiệu lực ngay ở request kế tiếp.
 * Không chứa mật khẩu hay hash.
 *
 * @param unitId    null với Super Admin
 * @param unitCode  null với Super Admin
 * @param unitLevel null với Super Admin. Cấp admin chính là cấp của đơn vị (không lưu riêng).
 * @param mustChangePassword mật khẩu do người khác đặt; chỉ được đổi mật khẩu hoặc đăng xuất ({@link PasswordChangeGate})
 */
public record UserPrincipal(
        long userId,
        String username,
        Role role,
        Long unitId,
        String unitCode,
        Integer unitLevel,
        String displayName,
        boolean mustChangePassword) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Role { USER, ADMIN, SUPER_ADMIN }

    public boolean isSuperAdmin() {
        return role == Role.SUPER_ADMIN;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public Collection<? extends GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
