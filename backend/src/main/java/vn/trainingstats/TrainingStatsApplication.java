package vn.trainingstats;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class TrainingStatsApplication {

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--reset-superadmin")) {
            System.exit(runResetTool(args));
        }
        SpringApplication.run(TrainingStatsApplication.class, args);
    }

    /** Chạy công cụ đặt lại Super Admin, không mở cổng web. Trả mã thoát 0 khi thành công. */
    static int runResetTool(String[] args) {
        SpringApplication app = new SpringApplication(TrainingStatsApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(Map.of(
                "app.reset-superadmin", "true",
                "app.first-run.enabled", "false",
                "spring.main.banner-mode", "off",
                "logging.level.root", "WARN"));
        try (ConfigurableApplicationContext ignored = app.run(args)) {
            return 0;
        } catch (RuntimeException e) {
            Throwable t = e;
            while (t.getCause() != null) {
                t = t.getCause();
            }
            // Console Windows thường không hiển thị đúng tiếng Việt có dấu, nên in không dấu.
            System.err.println("LOI: " + withoutMarks(String.valueOf(t.getMessage())));
            return 1;
        }
    }

    static String withoutMarks(String s) {
        String r = s.replace('đ', 'd').replace('Đ', 'D');
        return Normalizer.normalize(r, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }
}
