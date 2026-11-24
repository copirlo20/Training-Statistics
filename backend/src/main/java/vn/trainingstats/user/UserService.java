package vn.trainingstats.user;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.trainingstats.audit.AuditAction;
import vn.trainingstats.audit.AuditService;
import vn.trainingstats.catalog.CatalogService;
import vn.trainingstats.common.ApiError;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.OptimisticLock;
import vn.trainingstats.common.PageQuery;
import vn.trainingstats.common.PageResult;
import vn.trainingstats.common.Times;
import vn.trainingstats.common.Validation;
import vn.trainingstats.common.VietnameseText;
import vn.trainingstats.security.PasswordPolicy;
import vn.trainingstats.security.Scope;
import vn.trainingstats.security.ScopeService;
import vn.trainingstats.security.UserGuards;
import vn.trainingstats.security.UserPrincipal;
import vn.trainingstats.unit.UnitService;
import vn.trainingstats.user.UserDtos.CreateRequest;
import vn.trainingstats.user.UserDtos.MoveResult;
import vn.trainingstats.user.UserDtos.ProfileInput;
import vn.trainingstats.user.UserDtos.UpdateRequest;
import vn.trainingstats.user.UserDtos.UserListItem;
import vn.trainingstats.user.UserDtos.UserView;
import vn.trainingstats.user.UserRepository.Target;

/**
 * Quản lý người dùng theo phạm vi nhánh (ARCHITECTURE mục 5).
 * <ul>
 *   <li>Mọi thao tác lấy người đích qua {@link #requireTarget}: ngoài phạm vi thì 404 như thể không tồn tại.</li>
 *   <li>Cấp admin suy ra từ đơn vị. Chuyển một ADMIN ra ngoài phạm vi của người chuyển thì tự hạ về USER (R1).</li>
 *   <li>Không tự khóa, tự xóa, tự hạ quyền, tự chuyển đơn vị; luôn còn ít nhất một Super Admin hoạt động.</li>
 *   <li>Chỉ Super Admin cấp hoặc gỡ quyền Super Admin.</li>
 * </ul>
 * BCrypt chạy ngoài giao dịch; phần ghi DB và audit log nằm trong một giao dịch ngắn.
 */
@Service
public class UserService {

    /**
     * Mật khẩu của mọi tài khoản mới cấp mà không nhập mật khẩu (yêu cầu ngày 2026-11-19). Không qua PasswordPolicy:
     * đây là giá trị cố định, tăng độ dài tối thiểu trong cài đặt không được làm hỏng việc cấp tài khoản.
     */
    public static final String DEFAULT_PASSWORD = "123456789";

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,50}");
    private static final Pattern PHONE = Pattern.compile("[0-9+ .()-]{6,20}");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE", "DELETED");
    private static final Set<String> ROLES = Set.of("USER", "ADMIN", "SUPER_ADMIN");

    private final UserRepository users;
    private final UnitService units;
    private final CatalogService catalogs;
    private final ScopeService scopes;
    private final UserGuards guards;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public UserService(UserRepository users, UnitService units, CatalogService catalogs, ScopeService scopes,
                       UserGuards guards, PasswordPolicy passwordPolicy, PasswordEncoder encoder, AuditService audit,
                       JdbcTemplate jdbc, PlatformTransactionManager txManager, Clock clock) {
        this.users = users;
        this.units = units;
        this.catalogs = catalogs;
        this.scopes = scopes;
        this.guards = guards;
        this.passwordPolicy = passwordPolicy;
        this.encoder = encoder;
        this.audit = audit;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    // ================================================================== đọc

    /** Danh sách người dùng trong phạm vi. {@code unitIds} gồm cả đơn vị con; đơn vị ngoài phạm vi thì 404. */
    public PageResult<UserListItem> search(UserRepository.Filter filter, List<Long> unitIds, PageQuery page) {
        Scope scope = scopes.current();
        List<String> unitCodes = new ArrayList<>();
        for (Long unitId : unitIds == null ? List.<Long>of() : unitIds) {
            if (unitId == null) {
                continue;
            }
            UnitService.UnitView unit = units.require(unitId);
            if (!scope.coversUnit(unit.code())) {
                throw ApiException.notFound("Không tìm thấy đơn vị.");
            }
            unitCodes.add(unit.code());
        }
        if (filter.statuses().stream().anyMatch(s -> !STATUSES.contains(s) && !"ALL".equals(s))) {
            throw ApiException.invalidField("status", "Trạng thái không hợp lệ.");
        }
        if (filter.categories().stream().anyMatch(c -> !Category.CODES.contains(c))) {
            throw ApiException.invalidField("category", "Đối tượng phải là SQ, QNCN hoặc HSQ-CS.");
        }
        if (filter.roles().stream().anyMatch(r -> !ROLES.contains(r))) {
            throw ApiException.invalidField("role", "Quyền không hợp lệ.");
        }
        if (filter.kind() != null && !Set.of("PERSON", "UNIT").contains(filter.kind())) {
            throw ApiException.invalidField("kind", "Loại không hợp lệ.");
        }
        UserRepository.Filter f = new UserRepository.Filter(filter.q(), unitCodes, filter.rankIds(), filter.positionIds(),
                filter.specialtyIds(), filter.statuses(), filter.roles(), filter.kind(), filter.sort(), filter.dir(),
                filter.categories());
        return users.search(scope, f, page);
    }

    public UserView get(long id) {
        requireTarget(scopes.current(), id);
        return view(id);
    }

    // ================================================================== tạo, sửa

    public UserView create(CreateRequest req) {
        UserPrincipal actor = scopes.currentPrincipal();
        Validation v = new Validation();
        String role = req.role() == null || req.role().isBlank() ? "USER" : req.role().trim();
        // Đối tượng bắt buộc với mọi người có đơn vị (mọi vai trò trừ Super Admin).
        Profile profile = validateProfile(v, req.profile(), false, !"SUPER_ADMIN".equals(role));

        if (!ROLES.contains(role)) {
            v.add("role", "Quyền không hợp lệ.");
        }
        UnitService.UnitView unit = newUserUnit(v, actor, role, req.unitId());
        String username = validUsername(v, "username", req.username());
        String password = req.password() == null || req.password().isEmpty() ? null : req.password();
        checkAccount(v, role, username, password);
        v.throwIfAny();
        if (password != null) {
            passwordPolicy.validate("password", password);
        }
        String hash = username == null ? null : encoder.encode(password != null ? password : DEFAULT_PASSWORD);

        Long finalUnitId = unit == null ? null : unit.id();
        String finalUnitCode = unit == null ? null : unit.code();
        String finalUsername = username;
        long id = inTx(() -> {
            String now = Times.now(clock);
            // Tính mã trong giao dịch ghi: SQLite chỉ có một người ghi tại một thời điểm nên không cấp trùng.
            String code = profile.personalCode != null ? profile.personalCode : nextPersonalCode();
            Profile p = profile.withPersonalCode(code);
            try {
                jdbc.update("""
                        INSERT INTO app_user (kind, last_name, first_name, search_text, rank_id, position_id, unit_id,
                                              username, password_hash, role, status, personal_code, category, birth_date,
                                              phone, note, created_at, created_by, updated_at, updated_by)
                        VALUES ('PERSON', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, p.lastName, p.firstName, searchText(p, finalUsername), p.rankId,
                        p.positionId, finalUnitId, finalUsername, hash, role, code, p.category,
                        p.birthDate, p.phone, p.note, now, actor.userId(), now, actor.userId());
            } catch (DuplicateKeyException e) {
                throw duplicate(code, finalUsername, null);
            }
            long newId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
            users.replaceSpecialties(newId, p.specialtyIds);
            audit.record(AuditAction.USER_CREATED, "app_user", newId, finalUnitCode, null, view(newId));
            return newId;
        });
        return view(id);
    }

    /** Đơn vị của người mới tạo: null với Super Admin, hoặc khi đơn vị sai (lỗi đã ghi vào {@code v}). */
    private UnitService.UnitView newUserUnit(Validation v, UserPrincipal actor, String role, Long unitId) {
        if ("SUPER_ADMIN".equals(role)) {
            if (!actor.isSuperAdmin()) {
                throw ApiException.forbidden("FORBIDDEN", "Chỉ Super Admin được tạo tài khoản Super Admin.");
            }
            return null;
        }
        if (unitId == null) {
            v.add("unitId", "Chọn đơn vị.");
            return null;
        }
        UnitService.UnitView unit = units.find(unitId).orElse(null);
        if (unit == null || !scopes.of(actor).coversUnit(unit.code())) {
            v.add("unitId", "Đơn vị không tồn tại hoặc nằm ngoài phạm vi quản lý của bạn.");
            return null;
        }
        if (!unit.active()) {
            v.add("unitId", "Đơn vị đã bị khóa.");
            return null;
        }
        return unit;
    }

    /** Admin, Super Admin bắt buộc có tài khoản; có mật khẩu thì phải có tài khoản. */
    private static void checkAccount(Validation v, String role, String username, String password) {
        if (username != null || v.has("username")) {
            return;
        }
        if (!"USER".equals(role)) {
            v.add("username", "Admin và Super Admin bắt buộc có tài khoản.");
        } else if (password != null) {
            v.add("username", "Có mật khẩu thì phải có tài khoản.");
        }
    }

    public UserView update(long id, UpdateRequest req) {
        UserPrincipal actor = scopes.currentPrincipal();
        Target target = requireTarget(scopes.of(actor), id);
        requirePerson(target);
        Validation v = new Validation();
        Profile profile = validateProfile(v, req.profile(), true, !"SUPER_ADMIN".equals(target.role()));
        if (req.version() == null) {
            v.add("version", "Thiếu phiên bản dữ liệu, hãy tải lại trang.");
        }
        v.throwIfAny();

        inTx(() -> {
            UserView before = view(id);
            int n;
            try {
                n = jdbc.update("""
                        UPDATE app_user SET last_name = ?, first_name = ?, personal_code = ?, rank_id = ?, position_id = ?,
                               category = ?, birth_date = ?, phone = ?, note = ?, search_text = ?, updated_at = ?,
                               updated_by = ?, version = version + 1
                        WHERE id = ? AND version = ?
                        """, profile.lastName, profile.firstName, profile.personalCode, profile.rankId, profile.positionId,
                        profile.category,
                        profile.birthDate, profile.phone, profile.note, searchText(profile, before.username()),
                        Times.now(clock), actor.userId(), id, req.version());
            } catch (DuplicateKeyException e) {
                throw duplicate(profile.personalCode, null, id);
            }
            OptimisticLock.check(n);
            users.replaceSpecialties(id, profile.specialtyIds);
            audit.record(AuditAction.USER_UPDATED, "app_user", id, target.unitCode(), before,
                    view(id));
            return null;
        });
        return view(id);
    }

    // ================================================================== trạng thái, quyền, mật khẩu, đơn vị

    public UserView setStatus(long id, String status) {
        UserPrincipal actor = scopes.currentPrincipal();
        Target target = requireTarget(scopes.of(actor), id);
        requirePerson(target);
        if (status == null || !STATUSES.contains(status)) {
            throw ApiException.invalidField("status", "Trạng thái không hợp lệ.");
        }
        if (status.equals(target.status())) {
            return view(id);
        }
        inTx(() -> {
            if (!"ACTIVE".equals(status)) {
                guards.checkNotSelf(actor, id);
                guards.checkSuperAdminRemains(id);
            }
            UserView before = view(id);
            String now = Times.now(clock);
            boolean deleted = "DELETED".equals(status);
            jdbc.update("""
                    UPDATE app_user SET status = ?, deleted_at = ?, deleted_by = ?, updated_at = ?, updated_by = ?,
                           version = version + 1
                    WHERE id = ?
                    """, status, deleted ? now : null, deleted ? actor.userId() : null, now, actor.userId(), id);
            audit.record(AuditAction.USER_STATUS_CHANGED, "app_user", id, target.unitCode(),
                    Map.of("status", before.status()), Map.of("status", status));
            return null;
        });
        return view(id);
    }

    public UserView setRole(long id, String role, Long unitId) {
        UserPrincipal actor = scopes.currentPrincipal();
        Scope scope = scopes.of(actor);
        Target target = requireTarget(scope, id);
        requirePerson(target);
        if (role == null || !ROLES.contains(role)) {
            throw ApiException.invalidField("role", "Quyền không hợp lệ.");
        }
        if (role.equals(target.role())) {
            return view(id);
        }
        boolean touchesSuperAdmin = "SUPER_ADMIN".equals(role) || "SUPER_ADMIN".equals(target.role());
        if (touchesSuperAdmin && !actor.isSuperAdmin()) {
            throw ApiException.forbidden("FORBIDDEN", "Chỉ Super Admin được cấp hoặc gỡ quyền Super Admin.");
        }
        if (!"USER".equals(role) && (target.username() == null || !target.hasPassword())) {
            throw ApiException.invalidField("role",
                    "Người này chưa có tài khoản hoặc mật khẩu. Cấp tài khoản ở trang chi tiết trước khi cấp quyền.");
        }
        // Hạ Super Admin xuống thì cần một đơn vị (Super Admin không thuộc đơn vị nào).
        Long newUnitId = target.unitId();
        if ("SUPER_ADMIN".equals(role)) {
            newUnitId = null;
        } else if ("SUPER_ADMIN".equals(target.role())) {
            if (unitId == null) {
                throw ApiException.invalidField("unitId", "Chọn đơn vị cho người này khi bỏ quyền Super Admin.");
            }
            UnitService.UnitView unit = units.find(unitId).filter(UnitService.UnitView::active)
                    .orElseThrow(() -> ApiException.invalidField("unitId", "Đơn vị không tồn tại hoặc đã bị khóa."));
            newUnitId = unit.id();
        }
        Long finalUnitId = newUnitId;
        inTx(() -> {
            if (rank(role) < rank(target.role())) {
                guards.checkNotSelf(actor, id);
                guards.checkSuperAdminRemains(id);
            }
            UserView before = view(id);
            jdbc.update("""
                    UPDATE app_user SET role = ?, unit_id = ?, updated_at = ?, updated_by = ?, version = version + 1
                    WHERE id = ?
                    """, role, finalUnitId, Times.now(clock), actor.userId(), id);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("role", before.role());
            b.put("unitCode", before.unit() == null ? null : before.unit().code());
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("role", role);
            a.put("unitId", finalUnitId);
            audit.record(AuditAction.USER_ROLE_CHANGED, "app_user", id, target.unitCode(), b, a);
            return null;
        });
        return view(id);
    }

    /**
     * Cấp tài khoản (người chưa có), đổi tên tài khoản, hoặc thu hồi khi để trống (chỉ với Người dùng). Tài khoản chưa
     * có mật khẩu thì nhận {@link #DEFAULT_PASSWORD}; đổi tên giữ nguyên mật khẩu đang có.
     */
    public UserView setAccount(long id, String rawUsername) {
        UserPrincipal actor = scopes.currentPrincipal();
        Target target = requireTarget(scopes.of(actor), id);
        requirePerson(target);
        if ("SUPER_ADMIN".equals(target.role()) && !actor.isSuperAdmin()) {
            throw notFound();
        }
        Validation v = new Validation();
        String username = validUsername(v, "username", rawUsername);
        if (username == null && !"USER".equals(target.role()) && !v.has("username")) {
            v.add("username", "Admin và Super Admin bắt buộc có tài khoản.");
        }
        v.throwIfAny();
        if (Objects.equals(username, target.username())) {
            return view(id);
        }
        String hash = username != null && !target.hasPassword() ? encoder.encode(DEFAULT_PASSWORD) : null;
        inTx(() -> {
            UserView before = view(id);
            try {
                // Thu hồi tài khoản thì bỏ luôn mật khẩu (DB không cho có mật khẩu mà không có tài khoản).
                jdbc.update("""
                        UPDATE app_user SET username = ?,
                               password_hash = CASE WHEN ? IS NULL THEN NULL ELSE COALESCE(password_hash, ?) END,
                               search_text = ?, updated_at = ?, updated_by = ?, version = version + 1
                        WHERE id = ?
                        """, username, username, hash,
                        VietnameseText.searchKey(before.lastName(), before.firstName(), before.personalCode(), username),
                        Times.now(clock), actor.userId(), id);
            } catch (DuplicateKeyException e) {
                throw duplicate(null, username, id);
            }
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("username", before.username());
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("username", username);
            a.put("account", username == null ? "REMOVED" : before.username() == null ? "CREATED" : "RENAMED");
            audit.record(AuditAction.USER_UPDATED, "app_user", id, target.unitCode(), b, a);
            return null;
        });
        return view(id);
    }

    public void resetPassword(long id, String newPassword) {
        UserPrincipal actor = scopes.currentPrincipal();
        Target target = requireTarget(scopes.of(actor), id);
        requirePerson(target);
        if (actor.userId() == id) {
            throw ApiException.badRequest("SELF_RESET", "Đổi mật khẩu của chính mình ở trang Đổi mật khẩu.");
        }
        if ("SUPER_ADMIN".equals(target.role()) && !actor.isSuperAdmin()) {
            throw notFound();
        }
        if (target.username() == null) {
            throw ApiException.badRequest("NO_ACCOUNT", "Người này chưa có tài khoản. Thêm tài khoản trước.");
        }
        passwordPolicy.validate("newPassword", newPassword);
        String hash = encoder.encode(newPassword);
        inTx(() -> {
            jdbc.update("UPDATE app_user SET password_hash = ?, updated_at = ?, updated_by = ?, version = version + 1 WHERE id = ?",
                    hash, Times.now(clock), actor.userId(), id);
            audit.record(AuditAction.PASSWORD_RESET, "app_user", id, target.unitCode(), null, null);
            return null;
        });
    }

    /**
     * Chuyển sang BẤT KỲ đơn vị nào (PRD). Nếu người chuyển không phải Super Admin và đơn vị đích nằm ngoài phạm vi
     * của họ thì người được chuyển, nếu đang là ADMIN, bị hạ về USER, vì cấp admin suy ra từ đơn vị (R1).
     */
    public MoveResult move(long id, Long unitId) {
        UserPrincipal actor = scopes.currentPrincipal();
        Scope scope = scopes.of(actor);
        Target target = requireTarget(scope, id);
        requirePerson(target);
        if (actor.userId() == id) {
            throw ApiException.forbidden("SELF_ACTION", "Không được tự chuyển đơn vị của chính mình.");
        }
        if ("SUPER_ADMIN".equals(target.role())) {
            throw ApiException.badRequest("SUPER_ADMIN_NO_UNIT", "Super Admin không thuộc đơn vị nào.");
        }
        if (unitId == null) {
            throw ApiException.invalidField("unitId", "Chọn đơn vị chuyển đến.");
        }
        UnitService.UnitView dest = units.find(unitId)
                .orElseThrow(() -> ApiException.invalidField("unitId", "Không tìm thấy đơn vị."));
        if (!dest.active()) {
            throw ApiException.invalidField("unitId", "Đơn vị đã bị khóa.");
        }
        if (unitId.equals(target.unitId())) {
            throw ApiException.invalidField("unitId", "Người này đã ở đơn vị này.");
        }
        boolean stillInScope = scope.coversUnit(dest.code());
        boolean downgrade = !actor.isSuperAdmin() && "ADMIN".equals(target.role()) && !stillInScope;
        inTx(() -> {
            jdbc.update("""
                    UPDATE app_user SET unit_id = ?, role = ?, updated_at = ?, updated_by = ?, version = version + 1
                    WHERE id = ?
                    """, dest.id(), downgrade ? "USER" : target.role(), Times.now(clock), actor.userId(), id);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("unitCode", target.unitCode());
            b.put("role", target.role());
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("unitCode", dest.code());
            a.put("role", downgrade ? "USER" : target.role());
            a.put("downgraded", downgrade);
            audit.record(AuditAction.USER_MOVED, "app_user", id, target.unitCode(), b, a);
            return null;
        });
        return new MoveResult(downgrade, stillInScope,
                stillInScope ? view(id) : null);
    }

    // ================================================================== hỗ trợ

    /** Người đích trong phạm vi của người gọi. Không có hoặc ngoài phạm vi đều trả 404. */
    private Target requireTarget(Scope scope, long id) {
        return users.findTarget(id, scope).orElseThrow(UserService::notFound);
    }

    /** Chi tiết người trong phạm vi người đang gọi (sau khi đã kiểm bằng requireTarget hoặc vừa tạo trong phạm vi). */
    private UserView view(long id) {
        return users.findView(id, scopes.current()).orElseThrow(UserService::notFound);
    }

    private static void requirePerson(Target t) {
        if (!"PERSON".equals(t.kind())) {
            throw ApiException.badRequest("UNIT_RECORD", "Bản ghi đại diện đơn vị do hệ thống quản lý, không sửa được.");
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound("Không tìm thấy người dùng.");
    }

    private static int rank(String role) {
        return switch (role) {
            case "SUPER_ADMIN" -> 3;
            case "ADMIN" -> 2;
            default -> 1;
        };
    }

    private <T> T inTx(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private ApiException duplicate(String personalCode, String username, Long exceptId) {
        List<ApiError.FieldError> fields = new ArrayList<>();
        Integer codeTaken = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE personal_code = ? AND id <> ?",
                Integer.class, personalCode, exceptId == null ? -1 : exceptId);
        if (codeTaken != null && codeTaken > 0) {
            fields.add(new ApiError.FieldError("personalCode", "Mã định danh đã được dùng."));
        }
        if (username != null) {
            Integer userTaken = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username = ? AND id <> ?",
                    Integer.class, username, exceptId == null ? -1 : exceptId);
            if (userTaken != null && userTaken > 0) {
                fields.add(new ApiError.FieldError("username", "Tài khoản đã được dùng."));
            }
        }
        return new ApiException(HttpStatus.CONFLICT, "DUPLICATE", "Dữ liệu bị trùng với một người dùng đã có.", fields);
    }

    /** Hồ sơ đã kiểm. Dùng chung với nhập Excel (UserImportService). */
    record Profile(String lastName, String firstName, String personalCode, Long rankId, Long positionId,
                           List<Long> specialtyIds, String birthDate, String phone, String note, String category) {
        Profile withPersonalCode(String code) {
            return new Profile(lastName, firstName, code, rankId, positionId, specialtyIds, birthDate, phone, note,
                    category);
        }
    }

    /** Tiền tố mã định danh do hệ thống cấp: ND + ít nhất 6 chữ số (ND000001). */
    static final String CODE_PREFIX = "ND";

    /**
     * Mã định danh kế tiếp: số lớn nhất trong các mã dạng ND + chữ số (kể cả mã người dùng tự gõ đúng dạng này) cộng 1.
     * Gọi trong giao dịch ghi. Mã người dùng tự đặt khác dạng (số hiệu quân nhân...) không ảnh hưởng.
     */
    private String nextPersonalCode() {
        return formatAutoCode(maxAutoCodeNumber() + 1);
    }

    /** Số lớn nhất trong các mã dạng ND + chữ số đang có (0 nếu chưa có). */
    long maxAutoCodeNumber() {
        Long max = jdbc.queryForObject("""
                SELECT MAX(CAST(substr(personal_code, 3) AS INTEGER)) FROM app_user
                WHERE personal_code GLOB 'ND[0-9]*' AND substr(personal_code, 3) NOT GLOB '*[^0-9]*'
                """, Long.class);
        return max == null ? 0 : max;
    }

    static String formatAutoCode(long number) {
        return CODE_PREFIX + String.format("%06d", number);
    }

    /** Số trong mã dạng ND + chữ số, hoặc -1 nếu mã không có dạng đó. */
    static long autoCodeNumber(String code) {
        if (code == null || !code.matches(CODE_PREFIX + "[0-9]{1,15}")) {
            return -1;
        }
        return Long.parseLong(code.substring(CODE_PREFIX.length()));
    }

    /** {@code codeRequired} false khi tạo mới: để trống thì hệ thống tự cấp. Khi sửa thì bắt buộc (không xóa được mã). */
    Profile validateProfile(Validation v, ProfileInput in, boolean codeRequired, boolean categoryRequired) {
        String ln = v.required("lastName", in.lastName(), 100, "Nhập họ đệm.");
        String fn = v.required("firstName", in.firstName(), 50, "Nhập tên.");
        String code = codeRequired
                ? v.required("personalCode", in.personalCode(), 50, "Nhập mã định danh.")
                : v.optional("personalCode", in.personalCode(), 50);
        Long rankId = in.rankId();
        Long positionId = in.positionId();
        if (rankId != null && !catalogs.isActive(CatalogService.Type.RANKS, rankId)) {
            v.add("rankId", "Cấp bậc không hợp lệ.");
        }
        if (positionId != null && !catalogs.isActive(CatalogService.Type.POSITIONS, positionId)) {
            v.add("positionId", "Chức vụ không hợp lệ.");
        }
        List<Long> specs = in.specialtyIds() == null ? List.of() : in.specialtyIds().stream().distinct().toList();
        for (Long sid : specs) {
            if (sid == null || !catalogs.isActive(CatalogService.Type.SPECIALTIES, sid)) {
                v.add("specialtyIds", "Có chuyên ngành không hợp lệ.");
                break;
            }
        }
        String bd = v.pastDate("birthDate", in.birthDate());
        String ph = v.pattern("phone", v.optional("phone", in.phone(), 20), PHONE, "Số điện thoại không hợp lệ.");
        String nt = v.optional("note", in.note(), 500);
        String category = in.category();
        String cat = Category.parse(category);
        if (category != null && !category.isBlank() && cat == null) {
            v.add("category", "Đối tượng phải là SQ, QNCN hoặc HSQ-CS.");
        } else if (cat == null && categoryRequired) {
            v.add("category", "Chọn đối tượng (SQ, QNCN hoặc HSQ-CS).");
        }
        return new Profile(ln, fn, code, rankId, positionId, specs, bd, ph, nt, cat);
    }

    /** Tài khoản đã cắt khoảng trắng, hoặc null nếu để trống. Sai dạng thì thêm lỗi vào {@code field}. */
    static String validUsername(Validation v, String field, String raw) {
        return v.pattern(field, v.optional(field, raw, 50), USERNAME,
                "Tài khoản 3-50 ký tự, chỉ gồm chữ không dấu, số, dấu chấm, gạch dưới, gạch ngang.");
    }

    static String searchText(Profile p, String username) {
        return VietnameseText.searchKey(p.lastName, p.firstName, p.personalCode, username);
    }
}
