package mygrant.notifications;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import mygrant.notifications.dto.NotificationResponse;
import mygrant.policies.PolicyIndexedEvent;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/** Creates policy alerts for matching applicants and serves each user's inbox. */
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    public NotificationService(NotificationRepository notificationRepository, UserRepository userRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
    }

    /**
     * Notifies every applicant whose visa type the policy affects. Runs after the
     * ingestion commits, in its own transaction, so a failure here never undoes an import.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPolicyIndexed(PolicyIndexedEvent event) {
        List<String> visaTypes = event.visaTypes().stream().map(String::toLowerCase).toList();
        List<User> affected = userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, visaTypes);
        String message = buildMessage(event);

        notificationRepository.saveAll(affected.stream()
                .map(user -> new Notification(user.getId(), event.policyId(), message))
                .toList());
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(String email, Pageable pageable) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId(email), pageable)
                .map(NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public long unreadCount(String email) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId(email));
    }

    @Transactional
    public NotificationResponse markRead(String email, Long notificationId) {
        Notification notification = notificationRepository.findByIdAndUserId(notificationId, userId(email))
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));
        notification.markRead(Instant.now());
        return NotificationResponse.from(notification);
    }

    private Long userId(String email) {
        return userRepository.findByEmail(email).map(User::getId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));
    }

    private String buildMessage(PolicyIndexedEvent event) {
        String message = "New policy affecting " + String.join(", ", event.visaTypes()) + ": " + event.title();
        if (event.effectiveDate() != null) {
            message += " (effective " + event.effectiveDate() + ")";
        }
        return message.length() > 1500 ? message.substring(0, 1497) + "..." : message;
    }
}
