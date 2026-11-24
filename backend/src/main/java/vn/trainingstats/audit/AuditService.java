package vn.trainingstats.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vn.trainingstats.common.Times;
import vn.trainingstats.security.UserPrincipal;

/**
 * Ghi nhật ký thao tác. Nếu đang trong giao dịch ghi thì ghi cùng giao dịch (thao tác và log cùng thành công hoặc
 * cùng hoàn tác). Nếu không có giao dịch thì tự mở một giao dịch ngắn. Không dùng REQUIRES_NEW: pool ghi chỉ có
 * một kết nối, giao dịch lồng sẽ tự chờ chính mình.
 *
 * Mọi trường có tên chứa password, hash, token, secret trong before/after đều bị thay bằng "***".
 */
@Service
public class AuditService {

    /** Ghi log thay mặt hệ thống (khởi tạo, công cụ dòng lệnh), không có người đăng nhập. */
    public record Actor(Long id, String username) {
        public static final Actor SYSTEM = new Actor(null, "system");
    }

    private static final String[] SECRET_KEYS = {"password", "hash", "token", "secret"};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AuditService(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager txManager, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** Ghi log với người đang đăng nhập làm người thực hiện. */
    public void record(String action, String entityType, Long entityId, String unitCode, Object before, Object after) {
        recordAs(currentActor(), action, entityType, entityId, unitCode, before, after);
    }

    public void recordAs(Actor actor, String action, String entityType, Long entityId, String unitCode,
                         Object before, Object after) {
        String beforeJson = toJson(before);
        String afterJson = toJson(after);
        String ip = currentIp();
        Runnable insert = () -> jdbc.update("""
                INSERT INTO audit_log (at, actor_id, actor_username, action, entity_type, entity_id, unit_code,
                                       before_json, after_json, ip)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Times.now(clock), actor.id(), truncate(actor.username(), 100), action, entityType, entityId,
                unitCode, beforeJson, afterJson, ip);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                throw new IllegalStateException("Không ghi audit log được trong giao dịch readOnly");
            }
            insert.run();
        } else {
            tx.executeWithoutResult(status -> insert.run());
        }
    }

    public static Actor currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p) {
            return new Actor(p.userId(), p.username());
        }
        return Actor.SYSTEM;
    }

    private static String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }

    String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode node = mapper.valueToTree(value);
            return mapper.writeValueAsString(redact(node));
        } catch (IllegalArgumentException | JsonProcessingException e) {
            throw new IllegalStateException("Không chuyển được dữ liệu audit sang JSON", e);
        }
    }

    private static JsonNode redact(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (isSecret(e.getKey())) {
                    e.setValue(TextNode.valueOf("***"));
                } else {
                    redact(e.getValue());
                }
            }
        } else if (node instanceof ArrayNode arr) {
            arr.forEach(AuditService::redact);
        }
        return node;
    }

    private static boolean isSecret(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        for (String s : SECRET_KEYS) {
            if (k.contains(s)) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
