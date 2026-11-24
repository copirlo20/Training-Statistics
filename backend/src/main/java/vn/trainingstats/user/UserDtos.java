package vn.trainingstats.user;

import java.util.List;

/** Dữ liệu vào, ra của API người dùng. Không có trường nào chứa mật khẩu hay hash trong dữ liệu ra. */
public final class UserDtos {

    private UserDtos() {
    }

    public record Ref(long id, String name) {
    }

    public record RankRef(long id, String name, String symbol) {
    }

    public record UnitRef(long id, String code, String name, int level, String path) {
    }

    /** Chi tiết một người dùng. {@code adminLevel} bằng cấp của đơn vị khi là Admin. */
    public record UserView(long id, String kind, String lastName, String firstName, String fullName,
                           String personalCode, String category, RankRef rank, Ref position, UnitRef unit,
                           List<Ref> specialties,
                           String username, boolean hasPassword, String role, Integer adminLevel, String status,
                           String birthDate, String phone, String note, String lastLoginAt, int version,
                           String createdAt, String updatedAt) {
    }

    /** Một dòng trong danh sách. Cấp bậc hiển thị bằng ký hiệu (rankSymbol), tên đầy đủ để chú giải. */
    public record UserListItem(long id, String kind, String fullName, String personalCode, String category, String rankSymbol,
                               String rankName, String positionName, Long unitId, String unitCode, String unitName,
                               String username, String role, String status) {
    }

    /** Phần hồ sơ người nhập từ form hoặc file Excel, chưa kiểm (xem {@code UserService.validateProfile}). */
    public record ProfileInput(String lastName, String firstName, String personalCode, Long rankId, Long positionId,
                               List<Long> specialtyIds, String birthDate, String phone, String note, String category) {
    }

    public record CreateRequest(String lastName, String firstName, String personalCode, Long unitId, Long rankId,
                                Long positionId, List<Long> specialtyIds, String birthDate, String phone, String note,
                                String username, String password, String role, String category) {
        ProfileInput profile() {
            return new ProfileInput(lastName, firstName, personalCode, rankId, positionId, specialtyIds, birthDate, phone,
                    note, category);
        }
    }

    public record UpdateRequest(String lastName, String firstName, String personalCode, Long rankId, Long positionId,
                                List<Long> specialtyIds, String birthDate, String phone, String note, Integer version,
                                String category) {
        ProfileInput profile() {
            return new ProfileInput(lastName, firstName, personalCode, rankId, positionId, specialtyIds, birthDate, phone,
                    note, category);
        }
    }

    /** Cấp, đổi tên hoặc thu hồi (để trống) tài khoản đăng nhập. */
    public record AccountRequest(String username) {
    }

    public record StatusRequest(String status) {
    }

    /** {@code unitId} chỉ cần khi hạ một Super Admin xuống vai trò có đơn vị. */
    public record RoleRequest(String role, Long unitId) {
    }

    public record ResetPasswordRequest(String newPassword) {
    }

    public record MoveRequest(Long unitId) {
    }

    /**
     * @param downgraded  người được chuyển đã bị hạ từ ADMIN xuống USER (đích ngoài phạm vi người chuyển, R1)
     * @param stillInScope người chuyển còn quyền với người được chuyển hay không
     * @param user        thông tin sau khi chuyển, null nếu người chuyển không còn quyền xem
     */
    public record MoveResult(boolean downgraded, boolean stillInScope, UserView user) {
    }
}
