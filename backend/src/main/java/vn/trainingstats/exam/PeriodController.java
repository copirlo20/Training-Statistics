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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/periods")
public class PeriodController {

    public record StatusRequest(String status) {
    }

    private final PeriodService periods;

    public PeriodController(PeriodService periods) {
        this.periods = periods;
    }

    @GetMapping
    public List<PeriodService.PeriodView> list() {
        return periods.list();
    }

    @GetMapping("/{id}")
    public PeriodService.PeriodView get(@PathVariable long id) {
        return periods.require(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public PeriodService.PeriodView create(@RequestBody PeriodService.SaveRequest body) {
        return periods.create(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public PeriodService.PeriodView update(@PathVariable long id, @RequestBody PeriodService.SaveRequest body) {
        return periods.update(id, body);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public PeriodService.PeriodView status(@PathVariable long id, @RequestBody StatusRequest body) {
        return periods.setStatus(id, body.status());
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        periods.delete(id);
        return ResponseEntity.noContent().build();
    }
}
