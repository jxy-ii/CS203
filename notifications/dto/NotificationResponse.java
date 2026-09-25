package mygrant.notifications.dto;

import java.time.Instant;
import java.util.List;

import mygrant.common.Confidence;
import mygrant.notifications.ImpactSeverity;
import mygrant.notifications.Notification;
import mygrant.notifications.ProfileField;

/**
 * A notification as shown in the user's inbox. The severity, impact explanation and
 * confidence are null when the alert is rule-based only.
 */
public record NotificationResponse(
        Long id,
        Long policyId,
        String message,
        Instant createdAt,
        boolean read,
        boolean actionRequired,
        List<String> affectedFields,
        ImpactSeverity severity,
        String impactExplanation,
        Confidence confidence
) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(notification.getId(), notification.getPolicyId(),
                notification.getMessage(), notification.getCreatedAt(), notification.getReadAt() != null,
                notification.isActionRequired(),
                notification.getAffectedFields().stream().map(ProfileField::label).toList(),
                notification.getSeverity(), notification.getImpactExplanation(), notification.getConfidence());
    }
}
