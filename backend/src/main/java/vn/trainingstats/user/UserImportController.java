package vn.trainingstats.user;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.trainingstats.common.excel.ExcelTemplate;
import vn.trainingstats.common.excel.ImportReport;

/**
 * Nhập người dùng bằng Excel. File không bắt buộc ở tầng Spring (required = false) để kiểm quyền chạy trước: thiếu
 * file thì service báo lỗi, còn người không có quyền luôn nhận 403 dù gửi gì.
 */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class UserImportController {

    private final UserImportService imports;

    public UserImportController(UserImportService imports) {
        this.imports = imports;
    }

    @GetMapping("/import-template")
    public ResponseEntity<byte[]> template() {
        return ExcelTemplate.download("mau-nhap-nguoi-dung.xlsx", imports.template());
    }

    /** Mặc định chỉ kiểm tra ({@code dryRun=true}); gọi lại với {@code dryRun=false} để ghi. */
    @PostMapping("/import")
    public ImportReport importFile(@RequestParam(value = "file", required = false) MultipartFile file,
                                   @RequestParam(defaultValue = "true") boolean dryRun) {
        return imports.importFile(file, dryRun);
    }
}
