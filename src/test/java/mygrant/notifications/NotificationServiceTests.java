package mygrant.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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

    @Mock
    private NotificationStreamRegistry streamRegistry;

    @Captor
    private ArgumentCaptor<List<Notification>> saved;

    @BeforeEach
    void persistSavedRows() {
        lenient().when(notificationRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void notifiesApplicantsMatchingTheAffectedVisaTypes() {
        User student = user(7L, "student@example.com");
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1", "J-1")))
                .thenReturn(List.of(student));

        service().onPolicyIndexed(
                new PolicyIndexedEvent(3L, "Fixed Time Period of Admission", List.of("F-1", "J-1"),
                        LocalDate.parse("2026-10-01"), "Sets a fixed period of admission."));

        verify(notificationRepository).saveAll(saved.capture());
        verify(streamRegistry).publish(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.argThat(response -> response.message().contains("Fixed Time Period")));
        assertThat(saved.getValue()).singleElement().satisfies(notification -> {
            assertThat(notification.getUserId()).isEqualTo(7L);
            assertThat(notification.getPolicyId()).isEqualTo(3L);
            assertThat(notification.getMessage())
                    .contains("F-1, J-1", "Fixed Time Period of Admission", "2026-10-01");
            assertThat(notification.getReadAt()).isNull();
        });
    }

    @Test
    void pushFailureDoesNotFailNotificationCreation() {
        User student = user(7L, "student@example.com");
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1")))
                .thenReturn(List.of(student));
        doThrow(new IllegalStateException("closed stream")).when(streamRegistry)
                .publish(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any());

        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Fee update", List.of("F-1"), null, "Fees change."));

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
    }

    @Test
    void flagsActionRequiredWhenThePolicyTouchesAFilledInProfileField() {
        User student = user(7L, "student@example.com");
        student.setProgramEndDate(LocalDate.parse("2028-05-31"));
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1")))
                .thenReturn(List.of(student));

        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Fixed Time Period of Admission",
                List.of("F-1"), null, "Ends duration of status."));

        verify(notificationRepository).saveAll(saved.capture());
        verify(streamRegistry).publish(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.argThat(response -> response.actionRequired()
                        && response.affectedFields().contains("program end date")
                        && response.message().contains("Action needed: review your program end date")));
        assertThat(saved.getValue()).singleElement().satisfies(notification -> {
            assertThat(notification.isActionRequired()).isTrue();
            assertThat(notification.getAffectedFields()).containsExactly(ProfileField.PROGRAM_END_DATE);
            assertThat(notification.getMessage()).contains("Action needed: review your program end date");
        });
    }

    @Test
    void flagsOnlyTheVisaCategoryTheRuleAppliesTo() {
        User student = user(7L, "student@example.com");
        student.setProgramEndDate(LocalDate.parse("2028-05-31"));
        User worker = user(8L, "worker@example.com");
        worker.setVisaType("H-1B");
        worker.setProgramEndDate(LocalDate.parse("2028-05-31"));
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1", "H-1B")))
                .thenReturn(List.of(student, worker));

        // One rule classified into two categories: only the F-1 half concerns practical training.
        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Training and specialty occupation update",
                List.of("F-1", "H-1B"), null, "Revises optional practical training."));

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2);
        assertThat(saved.getValue()).filteredOn(notification -> notification.getUserId().equals(7L))
                .singleElement().satisfies(notification -> {
                    assertThat(notification.isActionRequired()).isTrue();
                    assertThat(notification.getAffectedFields())
                            .containsExactly(ProfileField.PROGRAM_END_DATE);
                });
        assertThat(saved.getValue()).filteredOn(notification -> notification.getUserId().equals(8L))
                .singleElement().satisfies(notification -> {
                    assertThat(notification.isActionRequired()).isFalse();
                    assertThat(notification.getMessage()).doesNotContain("Action needed");
                });
    }

    @Test
    void matchesUsersWhoseVisaTypeWasStoredWithStrayCaseOrSpacing() {
        User student = user(7L, "student@example.com");
        student.setVisaType(" f-1 "); // registration stores whatever was typed
        student.setProgramEndDate(LocalDate.parse("2028-05-31"));
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1")))
                .thenReturn(List.of(student));

        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Fixed Time Period of Admission",
                List.of("F-1"), null, "Ends duration of status."));

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement()
                .satisfies(notification -> assertThat(notification.isActionRequired()).isTrue());
    }

    @Test
    void sendsInformationalNotificationWhenNoRuleMatches() {
        User student = user(7L, "student@example.com");
        student.setProgramEndDate(LocalDate.parse("2028-05-31"));
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1")))
                .thenReturn(List.of(student));

        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Fee schedule update",
                List.of("F-1"), null, "Adjusts filing fees."));

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(notification -> {
            assertThat(notification.isActionRequired()).isFalse();
            assertThat(notification.getMessage()).doesNotContain("Action needed");
        });
    }

    @Test
    void doesNotFlagActionWhenTheUserHasNotFilledInTheAffectedField() {
        User student = user(7L, "student@example.com"); // no program end date, no academic level
        student.setAcademicLevel(null);
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1")))
                .thenReturn(List.of(student));

        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Fixed Time Period of Admission",
                List.of("F-1"), null, "Ends duration of status."));

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement()
                .satisfies(notification -> assertThat(notification.isActionRequired()).isFalse());
    }

    @Test
    void markReadOnlyFindsTheCallersOwnNotification() {
        when(userRepository.findByEmail("student@example.com"))
                .thenReturn(Optional.of(user(7L, "student@example.com")));
        when(notificationRepository.findByIdAndUserId(99L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service()
                .markRead("student@example.com", 99L))
                .isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    void markReadSetsReadTimestamp() {
        Notification notification = new Notification(7L, 3L, "msg", List.of());
        when(userRepository.findByEmail("student@example.com"))
                .thenReturn(Optional.of(user(7L, "student@example.com")));
        when(notificationRepository.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(notification));

        var response = service()
                .markRead("student@example.com", 1L);

        assertThat(response.read()).isTrue();
        assertThat(notification.getReadAt()).isNotNull();
    }

    private NotificationService service() {
        return new NotificationService(notificationRepository, userRepository, new PolicyImpactRules(), streamRegistry);
    }

    private User user(Long id, String email) {
        User user = new User("Student", email, "hash", "F-1", "Master's", null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
