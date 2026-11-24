package vn.trainingstats.notification;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.trainingstats.common.PageQuery;
import vn.trainingstats.common.PageResult;
import vn.trainingstats.notification.NotificationService.Count;
import vn.trainingstats.notification.NotificationService.NotificationView;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public PageResult<NotificationView> list(@RequestParam(required = false) Boolean unread,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return notifications.list(Boolean.TRUE.equals(unread), PageQuery.of(page, size));
    }

    @GetMapping("/unread-count")
    public Count unreadCount() {
        return notifications.unreadCount();
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable long id) {
        notifications.markRead(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public Count readAll() {
        return notifications.markAllRead();
    }
}
