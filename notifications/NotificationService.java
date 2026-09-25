package mygrant.notifications;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final PolicyImpactRules impactRules;
    private final PolicyImpactAssessor impactAssessor;

    public NotificationService(NotificationRepository notificationRepository, UserRepository userRepository,
            PolicyImpactRules impactRules, PolicyImpactAssessor impactAssessor) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.impactRules = impactRules;
        this.impactAssessor = impactAssessor;
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

        List<Notification> notifications = new ArrayList<>();
        boolean modelReachable = true;
        for (User user : affected) {
            Notification notification = toNotification(user, event, message, fieldsByVisaType);
            // Only users the rules flagged reach the model, so its cost scales with real
            // matches rather than with everyone holding the visa type.
            if (notification.isActionRequired() && modelReachable) {
                modelReachable = assessImpact(notification, user, event);
            }
            notifications.add(notification);
        }
        notificationRepository.saveAll(notifications);
    }

    /**
     * Adds the model's rating to a flagged alert. Any failure keeps the rule-based alert
     * unchanged, so the model can never stop a notification from being sent.
     *
     * @return false when the model could not be reached, so later users skip it
     */
    private boolean assessImpact(Notification notification, User user, PolicyIndexedEvent event) {
        try {
            impactAssessor.assess(user, event, notification.getAffectedFields())
                    .ifPresent(notification::recordAssessment);
            return true;
        } catch (RuntimeException exception) {
            if (isConnectionFailure(exception)) {
                // A timeout or refused connection will repeat for every remaining user. This
                // runs on the ingestion request, so stop waiting on the model for this policy.
                log.warn("Impact model unreachable for policy {} at user {}; remaining users keep the "
                        + "rule-based message", event.policyId(), user.getId(), exception);
                return false;
            }
            log.warn("Impact assessment failed for policy {} and user {}; keeping the rule-based message",
                    event.policyId(), user.getId(), exception);
            return true;
        }
    }

    // RestClient reports a refused connection as ResourceAccessException but a read timeout
    // as a plain RestClientException, so the I/O cause is the only reliable signal.
    private static boolean isConnectionFailure(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
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
