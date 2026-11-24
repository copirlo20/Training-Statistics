package vn.trainingstats.user;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.trainingstats.audit.AuditAction;
import vn.trainingstats.audit.AuditService;
import vn.trainingstats.catalog.CatalogService;
import vn.trainingstats.catalog.CatalogService.Item;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.Times;
import vn.trainingstats.common.Validation;
import vn.trainingstats.common.VietnameseText;
import vn.trainingstats.common.excel.ExcelBook;
import vn.trainingstats.common.excel.ExcelBook.Column;
import vn.trainingstats.common.excel.ExcelBook.DataRow;
import vn.trainingstats.common.excel.ExcelTemplate;
import vn.trainingstats.common.excel.ExcelTemplate.Field;
import vn.trainingstats.common.excel.ImportReport;
import vn.trainingstats.common.excel.ImportRunner;
import vn.trainingstats.security.Scope;
import vn.trainingstats.security.ScopeService;
import vn.trainingstats.security.UserPrincipal;
import vn.trainingstats.unit.UnitService;
import vn.trainingstats.unit.UnitService.UnitView;
import vn.trainingstats.user.UserDtos.ProfileInput;
import vn.trainingstats.user.UserRepository.CodeMatch;
import vn.trainingstats.user.UserService.Profile;

/**
 * Nhập người dùng bằng Excel (M5.2, ARCHITECTURE 3.11).
 * <ul>
 *   <li>Khớp theo mã định danh: có thì cập nhật, chưa có thì tạo; để trống mã thì tạo mới và tự cấp mã (A12).</li>
 *   <li>Cập nhật: ô để trống ở cột không bắt buộc thì GIỮ giá trị cũ (Excel không xóa dữ liệu). Đơn vị khác hiện tại
 *       là chuyển đơn vị, đơn vị đích vẫn phải trong phạm vi nên không có hạ quyền (R1).</li>
 *   <li>Mỗi dòng phải có đơn vị trong phạm vi người nhập; người được khớp phải trong phạm vi (không thì báo lỗi,
 *       không tiết lộ họ tên).</li>
 *   <li>Cấp tài khoản và quyền (Người dùng, Admin) được: tài khoản mới nhận {@link UserService#DEFAULT_PASSWORD}.
 *       Quyền để trống thì người mới là Người dùng, người đã có giữ quyền cũ. Admin bắt buộc có tài khoản (báo lỗi ngay
 *       ở bước kiểm tra). Người đã có tài khoản không đổi tên tài khoản bằng Excel; Super Admin không cấp bằng Excel.</li>
 *   <li>Ghi tất cả hoặc không ghi gì. Đọc file ngoài giao dịch; kiểm lại toàn bộ trong giao dịch ghi.</li>
 * </ul>
 */
@Service
public class UserImportService {

    static final String SHEET = "NguoiDung";

    static final List<Column> COLUMNS = List.of(
            new Column("personalCode", "Mã định danh", true),
            new Column("lastName", "Họ đệm *", true),
            new Column("firstName", "Tên *", true),
            new Column("unit", "Đơn vị *", true),
            new Column("category", "Đối tượng *", true),
            new Column("rank", "Cấp bậc", false),
            new Column("position", "Chức vụ", false),
            new Column("specialty1", "Chuyên ngành 1", false),
            new Column("specialty2", "Chuyên ngành 2", false),
            new Column("specialty3", "Chuyên ngành 3", false),
            new Column("birthDate", "Ngày sinh", false),
            new Column("phone", "Số điện thoại", false),
            new Column("username", "Tài khoản", false),
            new Column("role", "Quyền", false),
            new Column("note", "Ghi chú", false),
            // File cũ: một cột, nhiều chuyên ngành cách nhau bởi dấu ;. Vẫn đọc, file mẫu mới không còn cột này.
            new Column("specialties", "Chuyên ngành", false));

    private static final List<String> SPECIALTY_KEYS = List.of("specialty1", "specialty2", "specialty3", "specialties");

    /** Tên trường của validateProfile đổi sang tên cột trong file. */
    private static final Map<String, String> FIELD_COLUMN = Map.ofEntries(
            Map.entry("personalCode", "Mã định danh"), Map.entry("lastName", "Họ đệm"), Map.entry("firstName", "Tên"),
            Map.entry("rankId", "Cấp bậc"), Map.entry("positionId", "Chức vụ"), Map.entry("specialtyIds", "Chuyên ngành 1"),
            Map.entry("birthDate", "Ngày sinh"), Map.entry("phone", "Số điện thoại"), Map.entry("note", "Ghi chú"),
            Map.entry("username", "Tài khoản"), Map.entry("category", "Đối tượng"));

    private static final String CODE_COLUMN = "Mã định danh";
    private static final String ACCOUNT_COLUMN = "Tài khoản";
    private static final String ROLE_USER = "Người dùng";
    private static final String ROLE_ADMIN = "Admin";
    private static final Map<String, String> ROLE_NAMES = Map.of(
            VietnameseText.searchKey(ROLE_USER), "USER", "user", "USER",
            VietnameseText.searchKey(ROLE_ADMIN), "ADMIN");

    /**
     * Một dòng hợp lệ, sẵn sàng ghi. {@code existing} null là tạo mới. {@code username} null là không cấp (người đã có
     * tài khoản thì giữ nguyên). {@code role} là quyền sau khi ghi. {@code setPassword}: tài khoản chưa có mật khẩu,
     * nhận mật khẩu mặc định.
     */
    private record Planned(int row, Profile profile, UnitView unit, CodeMatch existing, boolean hasSpecialties,
                           String username, String role, boolean setPassword) {
    }

    private final UserService userService;
    private final UserRepository users;
    private final UnitService units;
    private final CatalogService catalogs;
    private final ScopeService scopes;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public UserImportService(UserService userService, UserRepository users, UnitService units, CatalogService catalogs,
                             ScopeService scopes, PasswordEncoder encoder, AuditService audit, JdbcTemplate jdbc,
                             PlatformTransactionManager txManager, Clock clock) {
        this.userService = userService;
        this.users = users;
        this.units = units;
        this.catalogs = catalogs;
        this.scopes = scopes;
        this.encoder = encoder;
        this.audit = audit;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    // ================================================================== file mẫu

    public byte[] template() {
        UserPrincipal me = scopes.currentPrincipal();
        Scope scope = scopes.of(me);
        List<UnitView> tree = units.tree();
        List<String> unitOptions = tree.stream()
                .filter(UnitView::active)
                .filter(u -> scope.coversUnit(u.code()))
                .map(u -> u.code() + " - " + u.name())
                .toList();
        String scopeText = scope.kind() == Scope.Kind.ALL ? "Toàn Lữ đoàn" : tree.stream()
                .filter(u -> u.code().equals(scope.unitCode())).map(u -> u.name() + " (" + u.code() + ") và các đơn vị trực thuộc")
                .findFirst().orElse(scope.unitCode());
        List<String> ranks = activeNames(CatalogService.Type.RANKS);
        List<String> positions = activeNames(CatalogService.Type.POSITIONS);
        List<String> specialties = activeNames(CatalogService.Type.SPECIALTIES);
        List<Field> fields = List.of(
                Field.code(COLUMNS.get(0), 18, 50, "Để trống: tạo người mới, hệ thống tự cấp mã (ND000001...). "
                        + "Có mã: cập nhật người mang mã đó. Tối đa 50 ký tự, không trùng trong file.", "ND000123"),
                Field.text(COLUMNS.get(1), 22, 100, "Họ và tên đệm, tối đa 100 ký tự.", "Nguyễn Văn"),
                Field.text(COLUMNS.get(2), 14, 50, "Tên, tối đa 50 ký tự.", "An"),
                Field.list(COLUMNS.get(3), 42, unitOptions,
                        "Chọn trong danh sách (mã - tên đơn vị). Chỉ có các đơn vị trong phạm vi bạn quản lý. "
                                + "Người đã có mà đổi đơn vị thì được chuyển sang đơn vị mới.",
                        unitOptions.isEmpty() ? "" : unitOptions.get(0)),
                Field.list(COLUMNS.get(4), 12, Category.CODES, "Bắt buộc với người mới: SQ (sĩ quan), QNCN (quân nhân "
                        + "chuyên nghiệp), HSQ-CS (hạ sĩ quan - chiến sĩ). Người đã có mà để trống thì giữ đối tượng cũ.", "SQ"),
                Field.list(COLUMNS.get(5), 20, ranks,
                        "Chọn TÊN cấp bậc trong danh sách, không gõ ký hiệu (1/, 4//...).", ranks.isEmpty() ? "" : ranks.get(0)),
                Field.list(COLUMNS.get(6), 24, positions, "Chọn trong danh sách chức vụ.",
                        positions.isEmpty() ? "" : positions.get(0)),
                Field.list(COLUMNS.get(7), 22, specialties, "Chọn chuyên ngành trong danh sách. Có nhiều chuyên ngành thì "
                        + "chọn tiếp ở cột Chuyên ngành 2, 3 (quá 3 thì thêm trên giao diện).", specialties.isEmpty() ? "" : specialties.get(0)),
                Field.list(COLUMNS.get(8), 22, specialties, "Chuyên ngành thứ hai (nếu có), chọn trong danh sách.",
                        specialties.size() > 1 ? specialties.get(1) : ""),
                Field.list(COLUMNS.get(9), 22, specialties, "Chuyên ngành thứ ba (nếu có), chọn trong danh sách.", ""),
                Field.date(COLUMNS.get(10), 14, true, "Ngày/tháng/năm, không sau hôm nay.", "05/09/1995"),
                Field.phone(COLUMNS.get(11), 16, "0912345678"),
                Field.text(COLUMNS.get(12), 18, 50, "Tài khoản đăng nhập, để trống nếu không cần đăng nhập. 3-50 ký tự: "
                        + "chữ không dấu, số, dấu chấm, gạch dưới, gạch ngang. Tài khoản mới có mật khẩu "
                        + UserService.DEFAULT_PASSWORD + ".", "nguyenvanan"),
                Field.list(COLUMNS.get(13), 14, List.of(ROLE_USER, ROLE_ADMIN), "Để trống: người mới là " + ROLE_USER
                        + ", người đã có giữ quyền cũ. Admin quản lý đơn vị của mình và cấp dưới, bắt buộc có tài khoản.",
                        ROLE_USER),
                Field.text(COLUMNS.get(14), 30, 500, "Tối đa 500 ký tự.", ""));
        try (ExcelTemplate t = new ExcelTemplate("Mẫu nhập người dùng")) {
            var data = t.dataSheet(SHEET, "Danh sách người dùng nhập vào hệ thống", List.of(
                    "Người tạo file: " + me.displayName(),
                    "Phạm vi được nhập: " + scopeText,
                    "Ngày tạo: " + Times.todayText(clock)), fields);
            t.guide("Hướng dẫn nhập người dùng", List.of(
                    "Điền ở sheet " + SHEET + ", mỗi dòng một người, bắt đầu ngay dưới dòng tiêu đề cột. "
                            + "Không xóa, đổi tên hay đổi thứ tự các dòng tiêu đề."),
                    List.of(new ExcelTemplate.GuideSection("Các cột của sheet " + SHEET, data.fields())),
                    List.of(
                            "Đơn vị, cấp bậc, chức vụ, chuyên ngành lấy từ danh mục của hệ thống: bấm mũi tên ở cạnh ô để chọn, không gõ tay.",
                            "Khớp người theo mã định danh: có mã đã tồn tại thì cập nhật người đó, ô để trống ở cột không bắt buộc thì giữ nguyên giá trị cũ. Đã chọn chuyên ngành thì danh sách chuyên ngành của người đó được thay bằng các chuyên ngành đã chọn.",
                            "Cấp tài khoản: điền cột Tài khoản. Tài khoản mới có mật khẩu " + UserService.DEFAULT_PASSWORD
                                    + ", người dùng phải đặt mật khẩu mới ở lần đăng nhập đầu. Người đã có tài khoản giữ nguyên tài khoản và mật khẩu; đổi tên hay thu hồi tài khoản làm trên trang chi tiết người dùng.",
                            "Cấp quyền: chọn ở cột Quyền (" + ROLE_USER + " hoặc " + ROLE_ADMIN + "). Admin bắt buộc có tài khoản. Quyền Super Admin không cấp bằng Excel.",
                            "Trên giao diện, bấm \"Kiểm tra file\" trước. Hệ thống báo lỗi theo dòng và cột; có một dòng lỗi thì không ghi dòng nào.",
                            "Tối đa " + ExcelBook.MAX_ROWS + " dòng, file tối đa 5 MB. Lưu file dạng Excel Workbook (.xlsx)."));
            return t.toBytes();
        }
    }

    private List<String> activeNames(CatalogService.Type type) {
        return catalogs.list(type).stream().filter(Item::active).map(Item::name).toList();
    }

    // ================================================================== nhập

    public ImportReport importFile(MultipartFile file, boolean dryRun) {
        UserPrincipal actor = scopes.currentPrincipal();
        List<DataRow> rows;
        String fileName;
        try (ExcelBook book = ExcelBook.open(file)) {
            rows = book.rows(SHEET, COLUMNS, true);
            fileName = book.fileName();
        }
        // BCrypt chậm nên băm một lần, ngoài giao dịch ghi; mọi tài khoản mới dùng chung mật khẩu mặc định.
        boolean mayCreateAccount = !dryRun && rows.stream().anyMatch(r -> Validation.trimToNull(r.get("username")) != null
                || Validation.trimToNull(r.get("role")) != null);
        String defaultHash = mayCreateAccount ? encoder.encode(UserService.DEFAULT_PASSWORD) : null;
        return ImportRunner.run(tx, dryRun, report -> plan(rows, actor, report),
                (planned, report) -> write(planned, actor, fileName, defaultHash));
    }

    private List<Planned> plan(List<DataRow> rows, UserPrincipal actor, ImportReport.Builder report) {
        report.rows(rows.size());
        RowPlanner planner = new RowPlanner(actor, report);
        List<Planned> out = new ArrayList<>();
        for (DataRow r : rows) {
            Planned p = planner.plan(r);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    /** Chuyên ngành đọc từ các cột chuyên ngành; {@code present} false khi mọi cột để trống (người đã có giữ nguyên). */
    private record Specialties(List<Long> ids, boolean present) {
    }

    /** Tài khoản và quyền sau khi ghi; {@code username} null là không cấp mới. */
    private record Account(String username, String role, String currentRole) {
    }

    /** Kiểm từng dòng với các bảng tra cứu nạp một lần cho cả file; nhớ mã, tài khoản đã gặp để báo trùng giữa các dòng. */
    private final class RowPlanner {
        private final UserPrincipal actor;
        private final ImportReport.Builder report;
        private final Scope scope;
        private final Map<String, UnitView> unitsByCode = new HashMap<>();
        private final Map<String, Item> ranks = catalogs.byName(CatalogService.Type.RANKS);
        private final Map<String, Item> positions = catalogs.byName(CatalogService.Type.POSITIONS);
        private final Map<String, Item> specialties = catalogs.byName(CatalogService.Type.SPECIALTIES);
        private final Map<String, Integer> codeRows = new HashMap<>();
        private final Map<String, Integer> usernameRows = new HashMap<>();

        RowPlanner(UserPrincipal actor, ImportReport.Builder report) {
            this.actor = actor;
            this.report = report;
            this.scope = scopes.of(actor);
            units.tree().forEach(u -> unitsByCode.put(u.code().toUpperCase(), u));
        }

        /** Dòng sẵn sàng ghi, hoặc null nếu dòng có lỗi (mọi lỗi của dòng đã ghi vào báo cáo). */
        Planned plan(DataRow r) {
            int row = r.number();
            int errorsBefore = report.errorCount();
            String code = Validation.trimToNull(r.get("personalCode"));
            CodeMatch existing = code == null ? null : matchExisting(code, row);
            UnitView unit = resolveUnit(r.get("unit"), unitsByCode, scope, report, row);
            if (existing != null && unit != null && existing.target().id() == actor.userId()
                    && !Objects.equals(existing.target().unitId(), unit.id())) {
                report.error(null, row, "Đơn vị", "Không tự chuyển đơn vị của chính mình.");
            }
            Long rankId = lookup(r.get("rank"), ranks, "Cấp bậc", report, row);
            Long positionId = lookup(r.get("position"), positions, "Chức vụ", report, row);
            Specialties picked = specialties(r);
            String birth = Validation.trimToNull(r.get("birthDate"));
            String birthIso = ExcelBook.isoDate(birth);
            if (birth != null && birthIso == null) {
                report.error(null, row, "Ngày sinh", "Ngày sinh phải có dạng dd/mm/yyyy.");
            }

            // Mục danh mục đã kiểm ở trên (kể cả đã khóa); validateProfile kiểm phần còn lại theo đúng quy tắc
            // của form tạo người dùng. Đối tượng bắt buộc với người mới; người đã có đối tượng mà để trống thì giữ nguyên.
            Validation v = new Validation();
            boolean categoryRequired = existing == null || existing.category() == null;
            Profile profile = userService.validateProfile(v, new ProfileInput(r.get("lastName"), r.get("firstName"), code,
                    rankId, positionId, picked.ids(), birthIso, r.get("phone"), r.get("note"), r.get("category")),
                    false, categoryRequired);
            String username = UserService.validUsername(v, "username", r.get("username"));
            v.errors().forEach(e -> report.error(null, row, FIELD_COLUMN.getOrDefault(e.field(), e.field()),
                    e.message()));
            UserRepository.Target t = existing == null ? null : existing.target();
            Account account = account(r, row, t, username, v.has("username"));

            if (report.errorCount() > errorsBefore) {
                return null;
            }
            boolean setPassword = (t == null || !t.hasPassword()) && (account.username() != null || isAdmin(account.role()));
            describe(row, code, profile, unit, existing, account);
            return new Planned(row, profile, unit, existing, picked.present(), account.username(), account.role(),
                    setPassword);
        }

        /** Người đã có mã này và được cập nhật, null nếu chưa có hoặc không được (đã báo lỗi). */
        private CodeMatch matchExisting(String code, int row) {
            Integer first = codeRows.putIfAbsent(code.toUpperCase(), row);
            if (first != null) {
                report.error(null, row, CODE_COLUMN, "Trùng mã định danh với dòng " + first + ".");
            }
            CodeMatch existing = users.findByCode(code).orElse(null);
            String problem = existing == null ? null : existingProblem(existing, scope);
            if (problem != null) {
                report.error(null, row, CODE_COLUMN, problem);
                return null;
            }
            return existing;
        }

        private Specialties specialties(DataRow r) {
            List<Long> ids = new ArrayList<>();
            boolean present = false;
            for (String key : SPECIALTY_KEYS) {
                String text = Validation.trimToNull(r.get(key));
                if (text == null) {
                    continue;
                }
                present = true;
                String column = "specialties".equals(key) ? "Chuyên ngành" : "Chuyên ngành " + key.substring(key.length() - 1);
                for (String part : text.split("[;\\n]")) {
                    Long id = part.isBlank() ? null : lookup(part, specialties, column, report, r.number());
                    if (id != null && !ids.contains(id)) {
                        ids.add(id);
                    }
                }
            }
            return new Specialties(ids, present);
        }

        /**
         * Tài khoản, quyền sau khi ghi. Người đã có tài khoản không đổi tên tài khoản bằng file; quyền để trống thì người
         * mới là Người dùng, người đã có giữ quyền cũ.
         */
        private Account account(DataRow r, int row, UserRepository.Target t, String username, boolean usernameInvalid) {
            String currentUsername = t == null ? null : t.username();
            String newUsername = username;
            if (username != null) {
                Integer first = usernameRows.putIfAbsent(username.toLowerCase(Locale.ROOT), row);
                if (first != null) {
                    report.error(null, row, ACCOUNT_COLUMN, "Trùng tài khoản với dòng " + first + ".");
                }
                if (currentUsername != null) {
                    if (!currentUsername.equalsIgnoreCase(username)) {
                        report.error(null, row, ACCOUNT_COLUMN, "Người này đã có tài khoản \"" + currentUsername
                                + "\". Đổi tài khoản trên trang chi tiết người dùng.");
                    }
                    newUsername = null;
                } else if (users.idByUsername(username).isPresent()) {
                    report.error(null, row, ACCOUNT_COLUMN, "Tài khoản \"" + username + "\" đã được dùng.");
                }
            }
            String fileRole = parseRole(r.get("role"), report, row);
            String currentRole = t == null ? null : t.role();
            String role = fileRole != null ? fileRole : t == null ? "USER" : currentRole;
            if (isAdmin(role) && newUsername == null && currentUsername == null && !usernameInvalid) {
                report.error(null, row, ACCOUNT_COLUMN, "Quyền " + ROLE_ADMIN + " bắt buộc có tài khoản.");
            }
            if (t != null && t.id() == actor.userId() && !role.equals(currentRole)) {
                report.error(null, row, "Quyền", "Không tự đổi quyền của chính mình.");
            }
            return new Account(newUsername, role, currentRole);
        }

        /** Dòng xem trước: tạo mới hay khớp với ai, chuyển đơn vị, cấp tài khoản, đổi quyền. */
        private void describe(int row, String code, Profile profile, UnitView unit, CodeMatch existing, Account account) {
            String name = profile.lastName() + " " + profile.firstName();
            List<String> extra = new ArrayList<>();
            if (account.username() != null) {
                extra.add("cấp tài khoản " + account.username());
            }
            if (!account.role().equals(account.currentRole()) && (existing != null || isAdmin(account.role()))) {
                extra.add("quyền " + roleName(account.role()));
            }
            String extraText = extra.isEmpty() ? "" : ", " + String.join(", ", extra);
            if (existing == null) {
                report.item(null, row, "CREATE", name,
                        (code == null ? "Mã tự cấp" : "Mã " + code) + " · " + unit.name() + extraText);
                return;
            }
            boolean moving = !Objects.equals(existing.target().unitId(), unit.id());
            report.item(null, row, "UPDATE", name, "Khớp với " + existing.fullName() + " (" + existing.unitName() + ")"
                    + (moving ? ", chuyển sang " + unit.name() : "") + extraText);
        }
    }

    private static boolean isAdmin(String role) {
        return "ADMIN".equals(role);
    }

    /** Lý do không cập nhật được người có mã này, hoặc null nếu được. */
    private static String existingProblem(CodeMatch m, Scope scope) {
        UserRepository.Target t = m.target();
        if (!"PERSON".equals(t.kind())) {
            return "Mã định danh không hợp lệ.";
        }
        if ("SUPER_ADMIN".equals(t.role())) {
            return "Mã định danh của tài khoản Super Admin, không cập nhật bằng Excel.";
        }
        // Không tiết lộ họ tên, đơn vị của người ngoài phạm vi.
        if (!scope.coversUser(t.id(), t.unitCode())) {
            return "Mã định danh đã được dùng cho một người ngoài phạm vi quản lý của bạn.";
        }
        if ("DELETED".equals(t.status())) {
            return "Người có mã này đã bị xóa. Khôi phục trên giao diện trước khi cập nhật.";
        }
        return null;
    }

    /** Quyền ghi trong file, hoặc null nếu để trống hay sai (sai thì đã báo lỗi). */
    private static String parseRole(String raw, ImportReport.Builder report, int row) {
        String text = Validation.trimToNull(raw);
        if (text == null) {
            return null;
        }
        String key = VietnameseText.searchKey(text);
        String role = ROLE_NAMES.get(key);
        if (role == null) {
            report.error(null, row, "Quyền", "superadmin".equals(key.replaceAll("[ _]", ""))
                    ? "Quyền Super Admin không cấp bằng Excel."
                    : "Quyền chỉ nhận \"" + ROLE_USER + "\" hoặc \"" + ROLE_ADMIN + "\".");
        }
        return role;
    }

    private static String roleName(String role) {
        return isAdmin(role) ? ROLE_ADMIN : ROLE_USER;
    }

    private static UnitView resolveUnit(String raw, Map<String, UnitView> byCode, Scope scope,
                                        ImportReport.Builder report, int row) {
        String text = Validation.trimToNull(raw);
        if (text == null) {
            report.error(null, row, "Đơn vị", "Nhập đơn vị.");
            return null;
        }
        // Ô chọn trong file mẫu có dạng "mã - tên"; cũng nhận chỉ mã.
        int dash = text.indexOf(" - ");
        String code = (dash > 0 ? text.substring(0, dash) : text).trim().toUpperCase();
        UnitView u = byCode.get(code);
        if (u == null) {
            report.error(null, row, "Đơn vị", "Không có đơn vị mã \"" + code + "\".");
            return null;
        }
        if (!scope.coversUnit(u.code())) {
            report.error(null, row, "Đơn vị", "Đơn vị " + u.code() + " nằm ngoài phạm vi quản lý của bạn.");
            return null;
        }
        if (!u.active()) {
            report.error(null, row, "Đơn vị", "Đơn vị " + u.code() + " đã bị khóa.");
            return null;
        }
        return u;
    }

    private static Long lookup(String raw, Map<String, Item> byName, String column, ImportReport.Builder report, int row) {
        String text = Validation.trimToNull(raw);
        if (text == null) {
            return null;
        }
        Item item = byName.get(VietnameseText.searchKey(text));
        if (item == null) {
            report.error(null, row, column, "Không có \"" + text + "\" trong danh mục " + column.toLowerCase() + ".");
            return null;
        }
        if (!item.active()) {
            report.error(null, row, column, "\"" + item.name() + "\" đã bị khóa trong danh mục.");
            return null;
        }
        return item.id();
    }

    // ================================================================== ghi (trong giao dịch)

    private void write(List<Planned> planned, UserPrincipal actor, String fileName, String defaultHash) {
        String now = Times.now(clock);
        // Mã tự cấp tiếp nối số lớn nhất đã có, kể cả các mã dạng ND... gõ sẵn trong file (để không cấp trùng).
        long next = userService.maxAutoCodeNumber();
        for (Planned p : planned) {
            next = Math.max(next, UserService.autoCodeNumber(p.profile().personalCode()));
        }
        int created = 0;
        int updated = 0;
        int moved = 0;
        int accounts = 0;
        List<String> codes = new ArrayList<>();
        for (Planned p : planned) {
            if (p.username() != null) {
                accounts++;
            }
            try {
                if (p.existing() == null) {
                    String code = p.profile().personalCode() != null ? p.profile().personalCode()
                            : UserService.formatAutoCode(++next);
                    insert(p, p.profile().withPersonalCode(code), actor, now, defaultHash);
                    codes.add(code);
                    created++;
                } else {
                    if (update(p, actor, now, defaultHash)) {
                        moved++;
                    }
                    codes.add(p.profile().personalCode());
                    updated++;
                }
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DUPLICATE", "Dòng " + p.row()
                        + ": mã định danh hoặc tài khoản vừa được người khác dùng. Hãy kiểm tra lại file.");
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("fileName", fileName);
        summary.put("created", created);
        summary.put("updated", updated);
        summary.put("moved", moved);
        summary.put("accounts", accounts);
        summary.put("personalCodes", codes);
        audit.record(AuditAction.USERS_IMPORTED, "app_user", null, null, null, summary);
    }

    private void insert(Planned planned, Profile p, UserPrincipal actor, String now, String defaultHash) {
        UnitView unit = planned.unit();
        jdbc.update("""
                INSERT INTO app_user (kind, last_name, first_name, search_text, rank_id, position_id, unit_id,
                                      username, password_hash, must_change_password, role, status, personal_code,
                                      category, birth_date, phone, note, created_at, created_by, updated_at, updated_by)
                VALUES ('PERSON', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, p.lastName(), p.firstName(), UserService.searchText(p, planned.username()), p.rankId(),
                p.positionId(), unit.id(), planned.username(), planned.setPassword() ? defaultHash : null,
                planned.setPassword() ? 1 : 0, planned.role(),
                p.personalCode(), p.category(), p.birthDate(), p.phone(), p.note(), now, actor.userId(), now, actor.userId());
        long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        users.replaceSpecialties(id, p.specialtyIds());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("source", "IMPORT");
        after.put("personalCode", p.personalCode());
        after.put("fullName", p.lastName() + " " + p.firstName());
        after.put("unitCode", unit.code());
        after.put("username", planned.username());
        after.put("role", planned.role());
        audit.record(AuditAction.USER_CREATED, "app_user", id, unit.code(), null, after);
    }

    /** Cập nhật người đã có. Trả true nếu có chuyển đơn vị. Ô trống ở cột không bắt buộc giữ giá trị cũ. */
    private boolean update(Planned planned, UserPrincipal actor, String now, String defaultHash) {
        UserRepository.Target t = planned.existing().target();
        Profile p = planned.profile();
        Profile forSearch = new Profile(p.lastName(), p.firstName(), p.personalCode(), null, null, List.of(), null, null, null,
                null);
        String username = planned.username() != null ? planned.username() : t.username();
        jdbc.update("""
                UPDATE app_user SET last_name = ?, first_name = ?, rank_id = COALESCE(?, rank_id),
                       position_id = COALESCE(?, position_id), category = COALESCE(?, category),
                       birth_date = COALESCE(?, birth_date),
                       phone = COALESCE(?, phone), note = COALESCE(?, note), unit_id = ?, username = ?,
                       must_change_password = CASE WHEN ? = 1 AND password_hash IS NULL THEN 1
                                                   ELSE must_change_password END,
                       password_hash = CASE WHEN ? = 1 THEN COALESCE(password_hash, ?) ELSE password_hash END,
                       role = ?, search_text = ?, updated_at = ?, updated_by = ?, version = version + 1
                WHERE id = ?
                """, p.lastName(), p.firstName(), p.rankId(), p.positionId(), p.category(), p.birthDate(), p.phone(), p.note(),
                planned.unit().id(), username, planned.setPassword() ? 1 : 0, planned.setPassword() ? 1 : 0, defaultHash,
                planned.role(),
                UserService.searchText(forSearch, username), now, actor.userId(), t.id());
        if (planned.hasSpecialties()) {
            users.replaceSpecialties(t.id(), p.specialtyIds());
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("fullName", planned.existing().fullName());
        before.put("unitCode", t.unitCode());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("source", "IMPORT");
        after.put("fullName", p.lastName() + " " + p.firstName());
        after.put("unitCode", planned.unit().code());
        if (planned.username() != null) {
            before.put("username", null);
            after.put("username", planned.username());
        }
        audit.record(AuditAction.USER_UPDATED, "app_user", t.id(), t.unitCode(), before, after);
        if (!planned.role().equals(t.role())) {
            audit.record(AuditAction.USER_ROLE_CHANGED, "app_user", t.id(), t.unitCode(),
                    Map.of("role", t.role()), Map.of("role", planned.role(), "source", "IMPORT"));
        }
        boolean moved = !Objects.equals(t.unitId(), planned.unit().id());
        if (moved) {
            audit.record(AuditAction.USER_MOVED, "app_user", t.id(), t.unitCode(),
                    Map.of("unitCode", t.unitCode()), Map.of("unitCode", planned.unit().code(), "source", "IMPORT"));
        }
        return moved;
    }
}
