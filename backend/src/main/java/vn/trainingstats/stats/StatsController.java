package vn.trainingstats.stats;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.trainingstats.common.PageQuery;
import vn.trainingstats.stats.StatsDtos.ByUnit;
import vn.trainingstats.stats.StatsDtos.ClassStats;
import vn.trainingstats.stats.StatsDtos.NotTestedPage;
import vn.trainingstats.stats.StatsDtos.Overview;
import vn.trainingstats.stats.StatsService.Params;

/**
 * API thống kê. Tham số lọc chung ({@link Filter}): kind (PERSON mặc định, UNIT), periodId, classId (nội dung kiểm
 * tra), unitId (một hoặc nhiều, cách nhau dấu phẩy; mỗi đơn vị gồm đơn vị con), from, to (ngày kiểm tra yyyy-MM-dd).
 * Thống kê một nội dung kiểm tra mở cho giám khảo của nội dung đó (có thể là người dùng thường).
 */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    /** Tham số lọc chung, gán theo tên tham số truy vấn; mọi tham số đều không bắt buộc. */
    public record Filter(String kind, Long periodId, Long classId, List<Long> unitId, String from, String to) {
        Params params() {
            return new Params(kind, periodId, classId, unitId, from, to);
        }
    }

    private final StatsService stats;

    public StatsController(StatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public Overview overview(Filter filter, @RequestParam(required = false) String group) {
        return stats.overview(filter.params(), group);
    }

    @GetMapping("/grade-by-unit")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ByUnit gradeByUnit(Filter filter, @RequestParam(required = false) String group) {
        return stats.gradeByUnit(filter.params(), group);
    }

    @GetMapping("/unit-scores")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ByUnit unitScores(Filter filter, @RequestParam(required = false) String group) {
        return stats.unitScores(filter.params(), group);
    }

    @GetMapping("/not-tested")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public NotTestedPage notTested(Filter filter, @RequestParam(required = false) String state,
                                   @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer size) {
        return stats.notTested(filter.params(), state, PageQuery.of(page, size));
    }

    @GetMapping("/class/{id}")
    public ClassStats classStats(@PathVariable long id, @RequestParam(required = false) String kind) {
        return stats.classStats(id, kind);
    }
}
