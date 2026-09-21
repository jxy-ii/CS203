package mygrant.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import mygrant.policies.PolicyIndexedEvent;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/** Verifies who is notified about a new policy and that inbox access is scoped to the owner. */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTests {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Captor
    private ArgumentCaptor<List<Notification>> saved;

    @Test
    void notifiesApplicantsMatchingTheAffectedVisaTypes() {
        User student = user(7L, "student@example.com");
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("f-1", "j-1")))
                .thenReturn(List.of(student));

        new NotificationService(notificationRepository, userRepository).onPolicyIndexed(
                new PolicyIndexedEvent(3L, "Fixed Time Period of Admission", List.of("F-1", "J-1"),
                        LocalDate.parse("2026-10-01")));

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(notification -> {
            assertThat(notification.getUserId()).isEqualTo(7L);
            assertThat(notification.getPolicyId()).isEqualTo(3L);
            assertThat(notification.getMessage())
                    .contains("F-1, J-1", "Fixed Time Period of Admission", "2026-10-01");
            assertThat(notification.getReadAt()).isNull();
        });
    }

    @Test
    void markReadOnlyFindsTheCallersOwnNotification() {
        when(userRepository.findByEmail("student@example.com"))
                .thenReturn(Optional.of(user(7L, "student@example.com")));
        when(notificationRepository.findByIdAndUserId(99L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new NotificationService(notificationRepository, userRepository)
                .markRead("student@example.com", 99L))
                .isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    void markReadSetsReadTimestamp() {
        Notification notification = new Notification(7L, 3L, "msg");
        when(userRepository.findByEmail("student@example.com"))
                .thenReturn(Optional.of(user(7L, "student@example.com")));
        when(notificationRepository.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(notification));

        var response = new NotificationService(notificationRepository, userRepository)
                .markRead("student@example.com", 1L);

        assertThat(response.read()).isTrue();
        assertThat(notification.getReadAt()).isNotNull();
    }

    private User user(Long id, String email) {
        User user = new User("Student", email, "hash", "F-1", "Master's", null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
