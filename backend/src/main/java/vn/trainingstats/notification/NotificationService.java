package vn.trainingstats.notification;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.JdbcValues;
import vn.trainingstats.common.PageQuery;
import vn.trainingstats.common.PageResult;
import vn.trainingstats.common.Times;
import vn.trainingstats.security.ScopeService;

/**
 * Thông báo trong ứng dụng. Mỗi người chỉ đọc được thông báo của chính mình (không phải của mình thì 404).
 * Thông báo được tạo trong cùng giao dịch với thao tác sinh ra nó, nên thao tác lỗi thì không có thông báo lạc.
 * Frontend hỏi số chưa đọc định kỳ (R18).
 */
@Service
public class NotificationService {

    public static final String TYPE_REQUEST_CREATED = "REQUEST_CREATED";
    public static final String TYPE_REQUEST_APPROVED = "REQUEST_APPROVED";
    public static final String TYPE_REQUEST_REJECTED = "REQUEST_REJECTED";
    public static final String REF_REQUEST = "class_request";

    public record NotificationView(long id, String type, String title, String body, String refType, Long refId,
                                   String createdAt, String readAt) {
    }

    public record Count(int count) {
    }

    private final JdbcTemplate jdbc;
    private final ScopeService scopes;
    private final Clock clock;

    public NotificationService(JdbcTemplate jdbc, ScopeService scopes, Clock clock) {
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.clock = clock;
    }

    /** Gửi cùng một thông báo cho nhiều người (bỏ trùng, bỏ người không còn hoạt động). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void send(Collection<Long> recipients, String type, String title, String body, String refType, Long refId) {
        String now = Times.now(clock);
        for (Long id : new LinkedHashSet<>(recipients)) {
            jdbc.update("""
                    INSERT INTO notification (recipient_id, type, title, body, ref_type, ref_id, created_at)
                    SELECT id, ?, ?, ?, ?, ?, ? FROM app_user WHERE id = ? AND status = 'ACTIVE' AND kind = 'PERSON'
                    """, type, truncate(title, 200), truncate(body, 1000), refType, refId, now, id);
        }
    }

    public PageResult<NotificationView> list(boolean unreadOnly, PageQuery page) {
        long me = scopes.currentPrincipal().userId();
        String where = " WHERE recipient_id = ?" + (unreadOnly ? " AND read_at IS NULL" : "");
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM notification" + where, Long.class, me);
        List<Object> params = new ArrayList<>(List.of(me, page.size(), page.offset()));
        List<NotificationView> items = jdbc.query("""
                SELECT id, type, title, body, ref_type, ref_id, created_at, read_at FROM notification
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new NotificationView(rs.getLong("id"), rs.getString("type"), rs.getString("title"),
                        rs.getString("body"), rs.getString("ref_type"),
                        JdbcValues.nullableLong(rs, "ref_id"), rs.getString("created_at"),
                        rs.getString("read_at")),
                params.toArray());
        return page.result(items, total == null ? 0 : total);
    }

    public Count unreadCount() {
        long me = scopes.currentPrincipal().userId();
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE recipient_id = ? AND read_at IS NULL",
                Integer.class, me);
        return new Count(n == null ? 0 : n);
    }

    @Transactional
    public void markRead(long id) {
        long me = scopes.currentPrincipal().userId();
        Integer mine = jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE id = ? AND recipient_id = ?",
                Integer.class, id, me);
        if (mine == null || mine == 0) {
            throw ApiException.notFound("Không tìm thấy thông báo.");
        }
        jdbc.update("UPDATE notification SET read_at = ? WHERE id = ? AND read_at IS NULL", Times.now(clock), id);
    }

    @Transactional
    public Count markAllRead() {
        long me = scopes.currentPrincipal().userId();
        int n = jdbc.update("UPDATE notification SET read_at = ? WHERE recipient_id = ? AND read_at IS NULL",
                Times.now(clock), me);
        return new Count(n);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
