package vn.trainingstats.request;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.trainingstats.common.PageQuery;
import vn.trainingstats.common.PageResult;
import vn.trainingstats.request.RequestDtos.CreateRequest;
import vn.trainingstats.request.RequestDtos.DecisionRequest;
import vn.trainingstats.request.RequestDtos.RequestView;

/**
 * API đề nghị. Tạo đề nghị chỉ dành cho Admin (Super Admin thêm trực tiếp).
 * Xem, duyệt, từ chối, hủy kiểm theo
 * từng đề nghị ở RequestService: giáo viên có thể là người dùng thường.
 */
@RequestMapping("/api/v1")
@RestController
public class RequestController {

    private final RequestService requests;

    public RequestController(RequestService requests) {
        this.requests = requests;
    }

    @PostMapping("/classes/{classId}/requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public RequestView create(@PathVariable long classId, @RequestBody CreateRequest body) {
        return requests.create(classId, body);
    }

    @GetMapping("/requests")
    public PageResult<RequestView> search(@RequestParam(required = false) String status,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return requests.search(status == null || status.isBlank() ? null : status.trim(), classId,
                PageQuery.of(page, size));
    }

    @GetMapping("/requests/{id}")
    public RequestView get(@PathVariable long id) {
        return requests.get(id);
    }

    @PostMapping("/requests/{id}/approve")
    public RequestView approve(@PathVariable long id, @RequestBody(required = false) DecisionRequest body) {
        return requests.approve(id, body == null ? null : body.note());
    }

    @PostMapping("/requests/{id}/reject")
    public RequestView reject(@PathVariable long id, @RequestBody(required = false) DecisionRequest body) {
        return requests.reject(id, body == null ? null : body.note());
    }

    @PostMapping("/requests/{id}/cancel")
    public RequestView cancel(@PathVariable long id) {
        return requests.cancel(id);
    }
}
