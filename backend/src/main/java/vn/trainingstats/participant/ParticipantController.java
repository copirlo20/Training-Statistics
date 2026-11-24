package vn.trainingstats.participant;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.trainingstats.participant.ParticipantDtos.AbsenceRequest;
import vn.trainingstats.participant.ParticipantDtos.AddRequest;
import vn.trainingstats.participant.ParticipantDtos.Participant;
import vn.trainingstats.participant.ParticipantDtos.RemoveRequest;

/**
 * API người học. Không giới hạn vai trò ở đây vì giáo viên có thể là người dùng thường: quyền theo lớp kiểm ở
 * ParticipantService (không liên quan đến lớp thì 404).
 */
@RestController
@RequestMapping("/api/v1/classes/{classId}")
public class ParticipantController {

    private final ParticipantService participants;

    public ParticipantController(ParticipantService participants) {
        this.participants = participants;
    }

    @GetMapping("/participants")
    public List<Participant> list(@PathVariable long classId, @RequestParam(required = false) String state) {
        return participants.list(classId, state);
    }

    @GetMapping("/candidates")
    public List<?> candidates(@PathVariable long classId, @RequestParam(required = false) String q,
                              @RequestParam(required = false) String kind) {
        return participants.candidates(classId, q, kind == null ? null : kind.trim());
    }

    @PostMapping("/participants")
    @ResponseStatus(HttpStatus.CREATED)
    public Participant add(@PathVariable long classId, @RequestBody AddRequest body) {
        return participants.add(classId, body);
    }

    @PostMapping("/participants/{pid}/remove")
    public Participant remove(@PathVariable long classId, @PathVariable long pid,
                              @RequestBody(required = false) RemoveRequest body) {
        return participants.remove(classId, pid, body == null ? null : body.reason());
    }

    @PutMapping("/participants/{pid}/absence")
    public Participant absence(@PathVariable long classId, @PathVariable long pid, @RequestBody AbsenceRequest body) {
        return participants.setAbsence(classId, pid, body);
    }
}
