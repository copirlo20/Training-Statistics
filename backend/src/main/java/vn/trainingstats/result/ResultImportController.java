package vn.trainingstats.result;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.trainingstats.common.excel.ExcelTemplate;
import vn.trainingstats.common.excel.ImportReport;

/**
 * Nhập và xuất điểm một lớp bằng Excel. Không giới hạn vai trò ở đây vì giáo viên có thể là người dùng thường: quyền
 * theo lớp kiểm ở service, trước khi đọc file (nhập: không quản lý lớp thì 404; xuất: không xem được lớp thì 404).
 */
@RestController
@RequestMapping("/api/v1/classes/{classId}/results")
public class ResultImportController {

    private final ResultImportService imports;
    private final ResultExportService exports;

    public ResultImportController(ResultImportService imports, ResultExportService exports) {
        this.imports = imports;
        this.exports = exports;
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@PathVariable long classId) {
        return ExcelTemplate.download("ket-qua-noi-dung-" + classId + ".xlsx", exports.export(classId));
    }

    @GetMapping("/import-template")
    public ResponseEntity<byte[]> template(@PathVariable long classId) {
        return ExcelTemplate.download("mau-nhap-diem-lop-" + classId + ".xlsx", imports.template(classId));
    }

    @PostMapping("/import")
    public ImportReport importFile(@PathVariable long classId,
                                   @RequestParam(value = "file", required = false) MultipartFile file,
                                   @RequestParam(defaultValue = "true") boolean dryRun) {
        return imports.importFile(classId, file, dryRun);
    }
}
