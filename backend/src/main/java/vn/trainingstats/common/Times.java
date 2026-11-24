package vn.trainingstats.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Quy ước thời gian: DB lưu TEXT ISO-8601 theo UTC, cùng định dạng với mặc định của schema
 * ({@code strftime('%Y-%m-%dT%H:%M:%fZ')}, ví dụ 2026-11-18T03:15:00.123Z) để so sánh chuỗi đúng thứ tự.
 * Hiển thị và "ngày" của người dùng tính theo giờ Việt Nam.
 */
public final class Times {

    public static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Ngày hiển thị cho người dùng, ví dụ 23/11/2026. */
    public static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final DateTimeFormatter DB_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Times() {
    }

    public static String format(Instant instant) {
        return DB_FORMAT.format(instant);
    }

    public static String now(Clock clock) {
        return format(clock.instant());
    }

    public static Instant parse(String dbValue) {
        return Instant.parse(dbValue);
    }

    /** Hôm nay theo giờ Việt Nam, dạng dd/MM/yyyy (dòng "Ngày tạo" của file mẫu). */
    public static String todayText(Clock clock) {
        return LocalDate.now(clock.withZone(DISPLAY_ZONE)).format(DMY);
    }

    /** Đầu ngày theo giờ Việt Nam, đổi sang chuỗi UTC dùng cho điều kiện {@code >=}. */
    public static String startOfDay(LocalDate localDate) {
        return format(localDate.atStartOfDay(DISPLAY_ZONE).toInstant());
    }

    /** Đầu ngày hôm sau theo giờ Việt Nam, dùng cho điều kiện {@code <} để lấy trọn ngày. */
    public static String startOfNextDay(LocalDate localDate) {
        return startOfDay(localDate.plusDays(1));
    }
}
