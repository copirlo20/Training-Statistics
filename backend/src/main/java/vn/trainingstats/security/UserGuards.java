package vn.trainingstats.security;

import org.springframework.stereotype.Service;
import vn.trainingstats.common.ApiException;

/**
 * Quy tắc chặn thao tác nguy hiểm lên tài khoản (ARCHITECTURE mục 5.1, quy tắc 4):
 * không tự khóa, tự xóa, tự hạ quyền chính mình, và hệ thống luôn còn ít nhất một Super Admin ACTIVE.
 * Gọi trong cùng giao dịch ghi với thao tác thay đổi.
 */
@Service
public class UserGuards {

    private final AccountRepository accounts;

    public UserGuards(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /** Dùng trước khi khóa, xóa mềm hoặc hạ quyền {@code targetUserId}. */
    public void checkNotSelf(UserPrincipal actor, long targetUserId) {
        if (actor.userId() == targetUserId) {
            throw ApiException.forbidden("SELF_ACTION",
                    "Không được tự khóa, tự xóa hoặc tự hạ quyền tài khoản của chính mình.");
        }
    }

    /** Dùng trước khi khóa, xóa mềm hoặc hạ quyền một tài khoản có thể là Super Admin. */
    public void checkSuperAdminRemains(long targetUserId) {
        if (accounts.isActiveSuperAdmin(targetUserId) && accounts.countActiveSuperAdminsExcept(targetUserId) == 0) {
            throw ApiException.conflict("LAST_SUPER_ADMIN",
                    "Đây là Super Admin còn hoạt động cuối cùng. Hệ thống phải luôn có ít nhất một Super Admin.");
        }
    }
}
