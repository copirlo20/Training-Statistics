package vn.trainingstats.admin;

import java.io.Console;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Công cụ dòng lệnh {@code --reset-superadmin [--username=admin]}: đặt lại mật khẩu (hoặc tạo) Super Admin.
 * Chỉ chạy được trên máy chủ, vì cần quyền đọc thư mục cài đặt và thư mục dữ liệu (chỉ SYSTEM, Administrators).
 * Không mở cổng web.
 *
 * Mật khẩu lấy từ biến môi trường {@value #PASSWORD_ENV} nếu có (dùng cho script), nếu không thì hỏi trên console.
 * Không nhận mật khẩu qua tham số dòng lệnh vì tham số hiện trong danh sách tiến trình.
 */
@Component
@Order(10)
@ConditionalOnProperty(name = "app.reset-superadmin", havingValue = "true")
public class ResetSuperAdminRunner implements ApplicationRunner {

    public static final String PASSWORD_ENV = "TS_RESET_PASSWORD";

    private final SuperAdminService superAdmins;

    public ResetSuperAdminRunner(SuperAdminService superAdmins) {
        this.superAdmins = superAdmins;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> names = args.getOptionValues("username");
        String username = names == null || names.isEmpty() ? SuperAdminService.DEFAULT_USERNAME : names.get(0).trim();
        char[] password = readPassword(username);
        try {
            SuperAdminService.ResetOutcome outcome = superAdmins.reset(username, new String(password));
            System.out.println(outcome == SuperAdminService.ResetOutcome.CREATED
                    ? "Da tao Super Admin '" + username + "'."
                    : "Da dat lai mat khau va mo khoa Super Admin '" + username + "'.");
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static char[] readPassword(String username) {
        String fromEnv = System.getenv(PASSWORD_ENV);
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return fromEnv.toCharArray();
        }
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("Khong co console de nhap mat khau. Dat bien moi truong " + PASSWORD_ENV + ".");
        }
        char[] first = console.readPassword("Mat khau moi cho '%s': ", username);
        char[] second = console.readPassword("Nhap lai mat khau: ");
        if (first == null || second == null || !Arrays.equals(first, second)) {
            throw new IllegalStateException("Hai lan nhap mat khau khong khop.");
        }
        Arrays.fill(second, '\0');
        return first;
    }
}
