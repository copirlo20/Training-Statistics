package vn.trainingstats.result;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.trainingstats.participant.ParticipantDtos.Participant;
import vn.trainingstats.result.ResultDtos.ClassResult;
import vn.trainingstats.result.ResultDtos.EditRequest;
import vn.trainingstats.result.ResultDtos.GradeRequest;
import vn.trainingstats.result.ResultDtos.HistoryEntry;

/**
 * API điểm và hồ sơ điểm. Nhập điểm không giới hạn vai trò ở đây vì giáo viên
 * có thể là người dùng thường: quyền
 * theo lớp kiểm ở ResultService (không phải giáo viên của lớp thì 404).
 */
@RequestMapping("/api/v1")
@RestController
public class ResultController {

    private final ResultService results;

    public ResultController(ResultService results) {
        this.results = results;
    }

    @PostMapping("/classes/{classId}/participants/{pid}/result")
    @ResponseStatus(HttpStatus.CREATED)
    public Participant grade(@PathVariable long classId, @PathVariable long pid,
            @RequestBody(required = false) GradeRequest body) {
        return results.grade(classId, pid, body);
    }

    @PutMapping("/results/{rid}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Participant edit(@PathVariable long rid, @RequestBody EditRequest body) {
        return results.edit(rid, body);
    }

    @GetMapping("/results/{rid}/history")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<HistoryEntry> history(@PathVariable long rid) {
        return results.history(rid);
    }

    /** Các lớp mình tham gia và điểm của mình. */
    @GetMapping("/me/classes")
    public List<ClassResult> myClasses() {
        return results.myClasses();
    }

    /**
     * Lịch sử điểm qua các đợt: chính mình, Admin trong nhánh, Super Admin. Người
     * khác trả 404.
     */
    @GetMapping("/users/{id}/history")
    public List<ClassResult> userHistory(@PathVariable long id) {
        return results.userHistory(id);
    }
}
