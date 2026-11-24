package vn.trainingstats.user;

import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.trainingstats.common.PageQuery;
import vn.trainingstats.common.PageResult;
import vn.trainingstats.user.UserDtos.AccountRequest;
import vn.trainingstats.user.UserDtos.CreateRequest;
import vn.trainingstats.user.UserDtos.MoveRequest;
import vn.trainingstats.user.UserDtos.MoveResult;
import vn.trainingstats.user.UserDtos.ResetPasswordRequest;
import vn.trainingstats.user.UserDtos.RoleRequest;
import vn.trainingstats.user.UserDtos.StatusRequest;
import vn.trainingstats.user.UserDtos.UpdateRequest;
import vn.trainingstats.user.UserDtos.UserListItem;
import vn.trainingstats.user.UserDtos.UserView;

/**
 * API người dùng. Quyền theo vai trò ở đây, quyền theo phạm vi nhánh trong {@link UserService}.
 * Xem chi tiết, in, lịch sử: người dùng thường gọi được nhưng chỉ cho chính mình (service trả 404 với người khác).
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public PageResult<UserListItem> search(SearchQuery query) {
        UserRepository.Filter filter = new UserRepository.Filter(query.q(), null, ids(query.rankId()),
                ids(query.positionId()), ids(query.specialtyId()), words(query.status()), words(query.role()),
                blankToNull(query.kind()), query.sort(), query.dir(), words(query.category()));
        return users.search(filter, ids(query.unitId()), PageQuery.of(query.page(), query.size()));
    }

    /**
     * Tham số lọc danh sách, gán theo tên tham số truy vấn. Mỗi tiêu chí nhận nhiều giá trị (lặp tham số hoặc cách nhau
     * bằng dấu phẩy), nhiều giá trị là "hoặc".
     */
    public record SearchQuery(String q, List<Long> unitId, List<Long> rankId, List<Long> positionId, List<Long> specialtyId,
                              List<String> status, List<String> role, List<String> category, String kind, String sort,
                              String dir, Integer page, Integer size) {
    }

    private static List<Long> ids(List<Long> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static List<String> words(List<String> values) {
        return values == null ? List.of() : values.stream().map(UserController::blankToNull)
                .filter(Objects::nonNull).distinct().toList();
    }

    @GetMapping("/{id}")
    public UserView get(@PathVariable long id) {
        return users.get(id);
    }

    /** Dữ liệu cho trang in thông tin cá nhân (cấp bậc tên đầy đủ, đường dẫn đơn vị). */
    @GetMapping("/{id}/print")
    public UserView print(@PathVariable long id) {
        return users.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public UserView create(@RequestBody CreateRequest body) {
        return users.create(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public UserView update(@PathVariable long id, @RequestBody UpdateRequest body) {
        return users.update(id, body);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public UserView status(@PathVariable long id, @RequestBody StatusRequest body) {
        return users.setStatus(id, body.status());
    }

    @PutMapping("/{id}/role")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public UserView role(@PathVariable long id, @RequestBody RoleRequest body) {
        return users.setRole(id, body.role(), body.unitId());
    }

    @PutMapping("/{id}/account")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public UserView account(@PathVariable long id, @RequestBody AccountRequest body) {
        return users.setAccount(id, body.username());
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Void> resetPassword(@PathVariable long id, @RequestBody ResetPasswordRequest body) {
        users.resetPassword(id, body.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/move")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public MoveResult move(@PathVariable long id, @RequestBody MoveRequest body) {
        return users.move(id, body.unitId());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
