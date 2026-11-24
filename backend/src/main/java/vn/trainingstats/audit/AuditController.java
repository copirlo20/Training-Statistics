package vn.trainingstats.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.PageQuery;
import vn.trainingstats.common.PageResult;
import vn.trainingstats.common.Times;

/** Xem nhật ký thao tác. Chỉ Super Admin (chặn cả ở SecurityConfig lẫn ở đây). Không có API sửa hay xóa. */
@RestController
@RequestMapping("/api/v1/audit-logs")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AuditController {

    public record AuditLogView(long id, String at, Long actorId, String actorUsername, String action,
                               String entityType, Long entityId, String unitCode, JsonNode before, JsonNode after,
                               String ip) {
    }

    private final AuditRepository repository;
    private final ObjectMapper mapper;

    public AuditController(AuditRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /**
     * @param from ngày bắt đầu (yyyy-MM-dd, theo giờ Việt Nam), tính cả ngày đó
     * @param to   ngày kết thúc (yyyy-MM-dd, theo giờ Việt Nam), tính cả ngày đó
     */
    @GetMapping
    public PageResult<AuditLogView> search(@RequestParam(required = false) Long actorId,
                                           @RequestParam(required = false) String actor,
                                           @RequestParam(required = false) String action,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        LocalDate fromDate = parseDate("from", from);
        LocalDate toDate = parseDate("to", to);
        if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) {
            throw ApiException.invalidField("to", "Ngày kết thúc không được trước ngày bắt đầu.");
        }
        AuditRepository.Filter filter = new AuditRepository.Filter(actorId, actor, action,
                fromDate == null ? null : Times.startOfDay(fromDate),
                toDate == null ? null : Times.startOfNextDay(toDate));
        PageResult<AuditRepository.Row> rows = repository.search(filter, PageQuery.of(page, size));
        return new PageResult<>(rows.items().stream().map(this::view).toList(), rows.page(), rows.size(), rows.total());
    }

    private AuditLogView view(AuditRepository.Row r) {
        return new AuditLogView(r.id(), r.at(), r.actorId(), r.actorUsername(), r.action(), r.entityType(),
                r.entityId(), r.unitCode(), json(r.beforeJson()), json(r.afterJson()), r.ip());
    }

    private JsonNode json(String s) {
        if (s == null) {
            return null;
        }
        try {
            return mapper.readTree(s);
        } catch (JsonProcessingException e) {
            return mapper.getNodeFactory().textNode(s);
        }
    }

    private static LocalDate parseDate(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.invalidField(field, "Ngày phải có dạng yyyy-MM-dd.");
        }
    }
}
