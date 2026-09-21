package mygrant.notifications.dto;

import java.time.Instant;

import mygrant.notifications.Notification;

/** A notification as shown in the user's inbox. */
public record NotificationResponse(
        Long id,
        Long policyId,
        String message,
        Instant createdAt,
        boolean read
) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(notification.getId(), notification.getPolicyId(),
                notification.getMessage(), notification.getCreatedAt(), notification.getReadAt() != null);
    }
}
