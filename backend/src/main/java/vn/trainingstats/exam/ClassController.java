package vn.trainingstats.exam;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.trainingstats.common.PageQuery;
import vn.trainingstats.common.PageResult;
import vn.trainingstats.exam.ClassDtos.ClassListItem;
import vn.trainingstats.exam.ClassDtos.ClassView;
import vn.trainingstats.exam.ClassDtos.OpenClass;
import vn.trainingstats.exam.ClassDtos.SaveRequest;
import vn.trainingstats.exam.ClassDtos.StatusRequest;
import vn.trainingstats.exam.ClassDtos.TeachersRequest;

/**
 * API lớp. GET /classes và GET /classes/{id} mở cho mọi người đã đăng nhập nhưng kết quả tự lọc theo quyền
 * (ClassService). Ghi chỉ Super Admin.
 */
@RestController
@RequestMapping("/api/v1/classes")
public class ClassController {

    private final ClassService classes;

    public ClassController(ClassService classes) {
        this.classes = classes;
    }

    @GetMapping
    public PageResult<ClassListItem> search(@RequestParam(required = false) Long periodId,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) Boolean teaching,
                                            @RequestParam(required = false) Boolean ungraded,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        return classes.search(periodId, status == null || status.isBlank() ? null : status.trim(),
                Boolean.TRUE.equals(teaching), Boolean.TRUE.equals(ungraded), PageQuery.of(page, size));
    }

    @GetMapping("/open")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public List<OpenClass> open() {
        return classes.openClasses();
    }

    @GetMapping("/{id}")
    public ClassView get(@PathVariable long id) {
        return classes.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ClassView create(@RequestBody SaveRequest body) {
        return classes.create(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ClassView update(@PathVariable long id, @RequestBody SaveRequest body) {
        return classes.update(id, body);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ClassView status(@PathVariable long id, @RequestBody StatusRequest body) {
        return classes.setStatus(id, body.status());
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        classes.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/teachers")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ClassView teachers(@PathVariable long id, @RequestBody TeachersRequest body) {
        return classes.setTeachers(id, body.userIds());
    }
}
