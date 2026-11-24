package vn.trainingstats.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.PageQuery;
import vn.trainingstats.common.Validation;
import vn.trainingstats.exam.ClassService;
import vn.trainingstats.security.ClassAccess;
import vn.trainingstats.security.Scope;
import vn.trainingstats.security.ScopeService;
import vn.trainingstats.security.UserPrincipal;
import vn.trainingstats.stats.StatsDtos.ByUnit;
import vn.trainingstats.stats.StatsDtos.ClassInfo;
import vn.trainingstats.stats.StatsDtos.ClassStats;
import vn.trainingstats.stats.StatsDtos.NotTestedPage;
import vn.trainingstats.stats.StatsDtos.Overview;
import vn.trainingstats.stats.StatsRepository.Filter;
import vn.trainingstats.unit.UnitService;

/**
 * Thống kê (M6.1). Admin, Super Admin xem trong phạm vi (đơn vị lúc vào lớp nằm trong nhánh). Thống kê một lớp còn mở
 * cho giáo viên của lớp đó (cả lớp); Admin xem thống kê lớp chỉ gồm người học thuộc nhánh mình.
 */
@Service
public class StatsService {

    /** Tham số lọc chung, nhận thô từ controller. */
    public record Params(String kind, Long periodId, Long classId, List<Long> unitIds, String from, String to) {
    }

    private static final Set<String> KINDS = Set.of("PERSON", "UNIT");
    private static final int PERIODS = 6;
    private static final int CONTENTS = 8;

    private final StatsRepository stats;
    private final ScopeService scopes;
    private final UnitService units;
    private final ClassService classes;
    private final ClassAccess classAccess;

    public StatsService(StatsRepository stats, ScopeService scopes, UnitService units, ClassService classes,
                        ClassAccess classAccess) {
        this.stats = stats;
        this.scopes = scopes;
        this.units = units;
        this.classes = classes;
        this.classAccess = classAccess;
    }

    public ByUnit gradeByUnit(Params p, String group) {
        Scope scope = scopes.current();
        Filter f = filter(p, scope);
        boolean top = top(group);
        return new ByUnit(f.kind(), top ? "top" : "unit", stats.byUnit(f, scope, top), stats.totals(f, scope));
    }

    /** Cùng số liệu với gradeByUnit nhưng xếp theo điểm trung bình (cao trước). */
    public ByUnit unitScores(Params p, String group) {
        ByUnit b = gradeByUnit(p, group);
        var rows = b.rows().stream()
                .sorted((x, y) -> Double.compare(avg(y), avg(x)))
                .toList();
        return new ByUnit(b.kind(), b.group(), rows, b.totals());
    }

    private static double avg(StatsDtos.UnitRow r) {
        return r.counts().avgX10() == null ? -1 : r.counts().avgX10();
    }

    public Overview overview(Params p, String group) {
        Scope scope = scopes.current();
        Filter f = filter(p, scope);
        return new Overview(f.kind(), stats.totals(f, scope), stats.byUnit(f, scope, top(group)),
                stats.byPeriod(f, scope, PERIODS), stats.byContent(f, scope, CONTENTS));
    }

    public NotTestedPage notTested(Params p, String state, PageQuery page) {
        String s = state == null || state.isBlank() ? "ALL" : state.trim();
        if (!Set.of("ALL", "ABSENT", "NOT_TESTED").contains(s)) {
            throw ApiException.invalidField("state", "Trạng thái phải là ALL, ABSENT hoặc NOT_TESTED.");
        }
        Scope scope = scopes.current();
        Filter f = filter(p, scope);
        long absent = stats.notTestedCount(f, scope, "ABSENT");
        long notTested = stats.notTestedCount(f, scope, "NOT_TESTED");
        long total = "ABSENT".equals(s) ? absent : "NOT_TESTED".equals(s) ? notTested : absent + notTested;
        return new NotTestedPage(stats.notTested(f, scope, s, page), page.page(), page.size(), total, absent, notTested,
                stats.notTestedReasons(f, scope));
    }

    /** Thống kê một lớp: Super Admin, giáo viên của lớp (cả lớp); Admin xem được lớp thì chỉ người thuộc nhánh mình. */
    public ClassStats classStats(long classId, String kind) {
        UserPrincipal me = scopes.currentPrincipal();
        boolean manage = classAccess.canManage(me, classId);
        if (!manage && !me.isAdmin()) {
            throw ApiException.notFound("Không tìm thấy nội dung kiểm tra.");
        }
        classes.requireVisible(me, classId);
        Scope scope = manage ? Scope.all(me.userId()) : scopes.of(me);
        Filter f = new Filter(kind(kind), null, classId, List.of(), null, null);
        var view = classes.get(classId);
        ClassInfo info = new ClassInfo(view.id(), view.name(), view.period().name(), view.examDate(), view.status());
        return new ClassStats(info, f.kind(), stats.totals(f, scope), stats.histogram(f, scope), stats.byUnit(f, scope, false));
    }

    // ================================================================== kiểm tham số

    private Filter filter(Params p, Scope scope) {
        Validation v = new Validation();
        String from = v.date("from", p.from());
        String to = v.date("to", p.to());
        if (from != null && to != null && from.compareTo(to) > 0) {
            v.add("to", "Đến ngày phải sau hoặc bằng từ ngày.");
        }
        v.throwIfAny();
        List<String> unitCodes = new ArrayList<>();
        for (Long unitId : p.unitIds() == null ? List.<Long>of() : p.unitIds()) {
            if (unitId == null) {
                continue;
            }
            UnitService.UnitView unit = units.find(unitId)
                    .filter(u -> scope.coversUnit(u.code()))
                    .orElseThrow(() -> ApiException.notFound("Không tìm thấy đơn vị."));
            unitCodes.add(unit.code());
        }
        return new Filter(kind(p.kind()), p.periodId(), p.classId(), unitCodes, from, to);
    }

    private static String kind(String kind) {
        String k = kind == null || kind.isBlank() ? "PERSON" : kind.trim();
        if (!KINDS.contains(k)) {
            throw ApiException.invalidField("kind", "Loại phải là PERSON (cá nhân) hoặc UNIT (đơn vị).");
        }
        return k;
    }

    private static boolean top(String group) {
        if (group == null || group.isBlank() || "unit".equals(group)) {
            return false;
        }
        if ("top".equals(group)) {
            return true;
        }
        throw ApiException.invalidField("group", "Cách gộp phải là unit hoặc top.");
    }
}
