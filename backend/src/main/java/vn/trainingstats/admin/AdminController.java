package vn.trainingstats.admin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.trainingstats.admin.BackupFiles.Entry;
import vn.trainingstats.admin.BackupService.Settings;
import vn.trainingstats.admin.BackupService.SettingsRequest;
import vn.trainingstats.admin.SystemHealthService.Health;

/** Quản trị hệ thống (M7.1, M7.3), chỉ Super Admin: sao lưu, cài đặt sao lưu, tình trạng hệ thống. */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminController {

    public record BackupList(Settings settings, List<Entry> items) {
    }

    private final BackupService backups;
    private final SystemHealthService health;

    public AdminController(BackupService backups, SystemHealthService health) {
        this.backups = backups;
        this.health = health;
    }

    @GetMapping("/health")
    public Health health() {
        return health.health();
    }

    @GetMapping("/backups")
    public BackupList backups() {
        return new BackupList(backups.settings(), backups.list());
    }

    /** Tạo ngay một bản sao lưu thủ công; tải về qua GET /admin/backups/{name}. */
    @PostMapping("/backup")
    @ResponseStatus(HttpStatus.CREATED)
    public Entry backup() {
        return backups.createManual();
    }

    @GetMapping("/backups/{name}")
    public ResponseEntity<Resource> download(@PathVariable String name) {
        Path file = backups.fileForDownload(name);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(file));
    }

    @PutMapping("/backup-settings")
    public Settings updateSettings(@RequestBody SettingsRequest body) {
        return backups.update(body);
    }
}
