package mygrant.notifications;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final PolicyImpactRules impactRules;
    private final NotificationStreamRegistry streamRegistry;

    public NotificationService(NotificationRepository notificationRepository, UserRepository userRepository,
            PolicyImpactRules impactRules, NotificationStreamRegistry streamRegistry) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.impactRules = impactRules;
        this.streamRegistry = streamRegistry;
    }

    /**
     * Notifies every applicant whose visa type the policy affects, flagging those who
     * must review profile information the policy touches. Runs after the
     * ingestion commits, in its own transaction, so a failure here never undoes an import.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPolicyIndexed(PolicyIndexedEvent event) {
        List<String> visaTypes = event.visaTypes().stream()
                .map(PolicyImpactRules::normalizeVisaType)
                .distinct()
                .toList();
        List<User> affected = userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, visaTypes);
        String message = buildMessage(event);
        // The rules are visa-specific, so a policy covering several categories affects
        // different fields per reader. Read the policy text once for all of them.
        Map<String, Set<ProfileField>> fieldsByVisaType =
                impactRules.affectedFieldsByVisaType(visaTypes, event.title(), event.indexedContent());

        List<Notification> saved = notificationRepository.saveAll(affected.stream()
                .map(user -> toNotification(user, event, message, fieldsByVisaType))
                .toList());
        // Publish only after these rows commit. A disconnected stream cannot undo persistence.
        Runnable publishSaved = () -> {
            for (Notification notification : saved) {
                try {
                    streamRegistry.publish(notification.getUserId(), NotificationResponse.from(notification));
                } catch (RuntimeException ignored) {
                    // A disconnected or unhealthy stream never rolls back alert creation.
                }
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishSaved.run();
        } else TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishSaved.run();
            }
        });
    }

    private Notification toNotification(User user, PolicyIndexedEvent event, String message,
            Map<String, Set<ProfileField>> fieldsByVisaType) {
        Set<ProfileField> policyFields = fieldsByVisaType
                .getOrDefault(PolicyImpactRules.normalizeVisaType(user.getVisaType()), Set.of());
        List<ProfileField> toReview = policyFields.stream().filter(field -> field.isFilledIn(user)).toList();
        if (toReview.isEmpty()) {
            return new Notification(user.getId(), event.policyId(), message, toReview);
        }
        String labels = toReview.stream().map(ProfileField::label).collect(Collectors.joining(", "));
        return new Notification(user.getId(), event.policyId(),
                truncate(message + ". Action needed: review your " + labels + "."), toReview);
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
        return truncate(message);
    }

    private String truncate(String message) {
        return message.length() > 1500 ? message.substring(0, 1497) + "..." : message;
    }
}
