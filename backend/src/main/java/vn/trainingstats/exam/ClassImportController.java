package vn.trainingstats.exam;

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

/** Nhập lớp bằng Excel, chỉ Super Admin. File không bắt buộc ở tầng Spring để kiểm quyền chạy trước. */
@RestController
@RequestMapping("/api/v1/classes")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class ClassImportController {

    private final ClassImportService imports;

    public ClassImportController(ClassImportService imports) {
        this.imports = imports;
    }

    @GetMapping("/import-template")
    public ResponseEntity<byte[]> template() {
        return ExcelTemplate.download("mau-nhap-lop.xlsx", imports.template());
    }

    @PostMapping("/import")
    public ImportReport importFile(@RequestParam(value = "file", required = false) MultipartFile file,
                                   @RequestParam(required = false) Long periodId,
                                   @RequestParam(defaultValue = "true") boolean dryRun) {
        return imports.importFile(file, periodId, dryRun);
    }
}
