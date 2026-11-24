package vn.trainingstats;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Thư mục tạm cho DB của test. Không xóa được ngay khi test xong (pool kết nối còn giữ file tới lúc JVM tắt), nên mỗi
 * lần tạo mới thì dọn các thư mục "trainingstats-*" cũ hơn 1 giờ của những lần chạy trước.
 */
final class TestDirs {

    private static final String PREFIX = "trainingstats-";
    private static final Duration KEEP = Duration.ofHours(1);
    private static volatile boolean cleaned = false;

    private TestDirs() {
    }

    static Path create(String name) {
        try {
            cleanOld();
            return Files.createTempDirectory(PREFIX + name + "-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static synchronized void cleanOld() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Instant limit = Instant.now().minus(KEEP);
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(tmp, PREFIX + "*")) {
            for (Path dir : dirs) {
                FileTime modified = Files.getLastModifiedTime(dir);
                if (Files.isDirectory(dir) && modified.toInstant().isBefore(limit)) {
                    deleteQuietly(dir);
                }
            }
        } catch (IOException ignored) {
            // Dọn rác là phụ, không làm hỏng test.
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // File còn bị khóa thì để lần sau.
                }
            });
        } catch (IOException ignored) {
            // Như trên.
        }
    }
}
