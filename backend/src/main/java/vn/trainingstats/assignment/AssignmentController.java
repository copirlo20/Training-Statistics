package vn.trainingstats.assignment;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.trainingstats.assignment.AssignmentDtos.ApplyRequest;
import vn.trainingstats.assignment.AssignmentDtos.ApplyResult;
import vn.trainingstats.assignment.AssignmentDtos.Matrix;
import vn.trainingstats.assignment.AssignmentDtos.MatrixQuery;
import vn.trainingstats.assignment.AssignmentDtos.UnitOption;
import vn.trainingstats.common.excel.ExcelTemplate;
import vn.trainingstats.common.excel.ImportReport;

/**
 * Bảng phân công. Không giới hạn vai trò ở đây vì giám khảo có thể là người dùng thường: quyền kiểm ở
 * AssignmentService (Super Admin, giám khảo, Admin; người khác nhận 404).
 */
@RestController
@RequestMapping("/api/v1/assignments")
public class AssignmentController {

    private final AssignmentService assignments;
    private final AssignmentExcelService excel;

    public AssignmentController(AssignmentService assignments, AssignmentExcelService excel) {
        this.assignments = assignments;
        this.excel = excel;
    }

    @GetMapping("/matrix")
    public Matrix matrix(MatrixQuery query) {
        return assignments.matrix(query);
    }

    @GetMapping("/units")
    public List<UnitOption> units(@RequestParam long periodId) {
        return assignments.unitOptions(periodId);
    }

    @PostMapping("/apply")
    public ApplyResult apply(@RequestBody ApplyRequest body) {
        return assignments.apply(body);
    }

    /** File mẫu phân công của một đợt: mỗi đơn vị trong phạm vi một sheet. */
    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@RequestParam long periodId) {
        return ExcelTemplate.download("mau-phan-cong-dot-" + periodId + ".xlsx", excel.template(periodId));
    }

    /** Nhập file phân công. {@code file} không bắt buộc ở tầng Spring để kiểm quyền trước khi đọc file. */
    @PostMapping("/import")
    public ImportReport importFile(@RequestParam long periodId,
                                   @RequestParam(value = "file", required = false) MultipartFile file,
                                   @RequestParam(defaultValue = "true") boolean dryRun) {
        return excel.importFile(periodId, file, dryRun);
    }
}
