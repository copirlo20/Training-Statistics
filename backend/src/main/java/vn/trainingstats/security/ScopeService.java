package vn.trainingstats.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import vn.trainingstats.common.ApiException;

/** Lấy người đang đăng nhập và phạm vi dữ liệu của họ. */
@Service
public class ScopeService {

    public Scope of(UserPrincipal principal) {
        return switch (principal.role()) {
            case SUPER_ADMIN -> Scope.all(principal.userId());
            case ADMIN -> Scope.unitTree(principal.unitCode(), principal.userId());
            case USER -> Scope.self(principal.userId());
        };
    }

    public Scope current() {
        return of(currentPrincipal());
    }

    public UserPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p) {
            return p;
        }
        throw ApiException.unauthorized("UNAUTHENTICATED", "Bạn cần đăng nhập.");
    }
}
