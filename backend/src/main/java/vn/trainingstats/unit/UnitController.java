package vn.trainingstats.unit;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.trainingstats.common.ApiException;

@RestController
@RequestMapping("/api/v1/units")
public class UnitController {

    public record StatusRequest(Boolean active) {
    }

    private final UnitService units;

    public UnitController(UnitService units) {
        this.units = units;
    }

    /** Toàn bộ cây (chỉ mã, tên, cấp). Admin cần cả cây để chuyển người dùng sang đơn vị bất kỳ. */
    @GetMapping("/tree")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public List<UnitService.UnitView> tree() {
        return units.tree();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public UnitService.UnitView create(@RequestBody UnitService.CreateRequest body) {
        return units.create(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public UnitService.UnitView update(@PathVariable long id, @RequestBody UnitService.UpdateRequest body) {
        return units.update(id, body);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public UnitService.UnitView status(@PathVariable long id, @RequestBody StatusRequest body) {
        if (body.active() == null) {
            throw ApiException.invalidField("active", "Thiếu trạng thái.");
        }
        return units.setActive(id, body.active());
    }
}
