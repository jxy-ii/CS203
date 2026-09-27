package mygrant.notifications;

import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import mygrant.notifications.dto.NotificationResponse;

/** The logged-in user's notification inbox. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationStream notificationStream;

    public NotificationController(NotificationService notificationService, NotificationStream notificationStream) {
        this.notificationService = notificationService;
        this.notificationStream = notificationStream;
    }

    @GetMapping
    public Page<NotificationResponse> list(Authentication authentication,
            @PageableDefault(size = 20) Pageable pageable) {
        return notificationService.list(authentication.getName(), pageable);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(Authentication authentication) {
        return Map.of("unread", notificationService.unreadCount(authentication.getName()));
    }

    /** Pushes each new notification to the caller as a server-sent {@code notification} event. */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication authentication) {
        return notificationStream.subscribe(authentication.getName());
    }

    @PostMapping("/{id}/read")
    public NotificationResponse markRead(Authentication authentication, @PathVariable Long id) {
        return notificationService.markRead(authentication.getName(), id);
    }
}
