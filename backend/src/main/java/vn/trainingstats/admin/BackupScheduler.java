package vn.trainingstats.admin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Sao lưu tự động hằng ngày (M7.1). Mỗi phút kiểm một lần: đã qua giờ {@code backup.time} (giờ Việt Nam) mà hôm nay
 * chưa có bản tự động thì sao lưu, nên máy tắt lúc 02:00 vẫn có bản sao lưu ngay khi bật lại trong ngày.
 * Tắt bằng {@code app.backup.auto=false} (test).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.backup.auto", havingValue = "true", matchIfMissing = true)
public class BackupScheduler {

    private final BackupService backups;

    public BackupScheduler(BackupService backups) {
        this.backups = backups;
    }

    @Scheduled(initialDelayString = "${app.backup.initial-delay-ms:60000}", fixedDelayString = "${app.backup.check-ms:60000}")
    void tick() {
        if (backups.autoDue()) {
            backups.runAuto();
        }
    }
}
