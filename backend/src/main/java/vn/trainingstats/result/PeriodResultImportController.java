package vn.trainingstats.result;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.trainingstats.common.excel.ExcelTemplate;
import vn.trainingstats.common.excel.ImportReport;

/** Chấm điểm cả đợt bằng Excel, chỉ Super Admin. File không bắt buộc ở tầng Spring để kiểm quyền chạy trước. */
@RestController
@RequestMapping("/api/v1/periods/{id}/results")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PeriodResultImportController {

    private final PeriodResultImportService imports;

    public PeriodResultImportController(PeriodResultImportService imports) {
        this.imports = imports;
    }

    @GetMapping("/import-template")
    public ResponseEntity<byte[]> template(@PathVariable long id) {
        return ExcelTemplate.download("mau-cham-diem-dot-" + id + ".xlsx", imports.template(id));
    }

    @PostMapping("/import")
    public ImportReport importFile(@PathVariable long id,
                                   @RequestParam(value = "file", required = false) MultipartFile file,
                                   @RequestParam(defaultValue = "true") boolean dryRun) {
        return imports.importFile(id, file, dryRun);
    }
}
