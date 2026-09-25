package mygrant.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;

import mygrant.common.Confidence;
import mygrant.policies.PolicyIndexedEvent;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/** Verifies who is notified about a new policy and that inbox access is scoped to the owner. */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class NotificationServiceTests {

    private static final PolicyIndexedEvent ADMISSION_RULE = new PolicyIndexedEvent(3L,
            "Fixed Time Period of Admission", List.of("F-1"), null, "Ends duration of status.");

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PolicyImpactAssessor impactAssessor;

    @Captor
    private ArgumentCaptor<List<Notification>> saved;

    @Test
    void notifiesApplicantsMatchingTheAffectedVisaTypes() {
        User student = user(7L, "student@example.com");
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1", "J-1")))
                .thenReturn(List.of(student));

        service().onPolicyIndexed(
                new PolicyIndexedEvent(3L, "Fixed Time Period of Admission", List.of("F-1", "J-1"),
                        LocalDate.parse("2026-10-01"), "Sets a fixed period of admission."));

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
    void flagsActionRequiredWhenThePolicyTouchesAFilledInProfileField() {
        User student = user(7L, "student@example.com");
        student.setProgramEndDate(LocalDate.parse("2028-05-31"));
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1")))
                .thenReturn(List.of(student));

        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Fixed Time Period of Admission",
                List.of("F-1"), null, "Ends duration of status."));

        verify(notificationRepository).saveAll(saved.capture());
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
    void neverAsksTheModelAboutUsersTheRulesDidNotFlag() {
        ChatClient chatClient = mock(ChatClient.class);
        User unfilled = user(7L, "student@example.com"); // F-1, but no program end date on file
        User worker = user(8L, "worker@example.com");
        worker.setVisaType("H-1B");
        worker.setProgramEndDate(LocalDate.parse("2028-05-31"));
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1", "H-1B")))
                .thenReturn(List.of(unfilled, worker));

        new NotificationService(notificationRepository, userRepository, new PolicyImpactRules(),
                new PolicyImpactAssessor(chatClient))
                .onPolicyIndexed(new PolicyIndexedEvent(3L, "Fixed Time Period of Admission",
                        List.of("F-1", "H-1B"), null, "Ends duration of status."));

        verifyNoInteractions(chatClient);
        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2)
                .allSatisfy(notification -> assertThat(notification.getSeverity()).isNull());
    }

    @Test
    void addsTheModelsRatingWithoutReplacingTheRuleMessage() {
        User student = user(7L, "student@example.com");
        student.setProgramEndDate(LocalDate.parse("2028-05-31"));
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1")))
                .thenReturn(List.of(student));
        when(impactAssessor.assess(student, ADMISSION_RULE, List.of(ProfileField.PROGRAM_END_DATE)))
                .thenReturn(Optional.of(new ImpactAssessment(ImpactSeverity.WARNING,
                        "Your program end date may no longer set how long you can stay.", Confidence.LOW)));

        service().onPolicyIndexed(ADMISSION_RULE);

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(notification -> {
            assertThat(notification.getSeverity()).isEqualTo(ImpactSeverity.WARNING);
            assertThat(notification.getConfidence()).isEqualTo(Confidence.LOW);
            assertThat(notification.getImpactExplanation()).startsWith("Your program end date");
            assertThat(notification.getMessage()).contains("Action needed: review your program end date");
        });
    }

    @Test
    void keepsTheRuleMessageWhenTheModelsAnswerIsUnusable() {
        User student = user(7L, "student@example.com");
        student.setProgramEndDate(LocalDate.parse("2028-05-31"));
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1")))
                .thenReturn(List.of(student));
        when(impactAssessor.assess(any(), any(), any())).thenReturn(Optional.empty());

        service().onPolicyIndexed(ADMISSION_RULE);

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(notification -> {
            assertThat(notification.getMessage()).isEqualTo("New policy affecting F-1: "
                    + "Fixed Time Period of Admission. Action needed: review your program end date.");
            assertThat(notification.isActionRequired()).isTrue();
            assertThat(notification.getSeverity()).isNull();
            assertThat(notification.getImpactExplanation()).isNull();
            assertThat(notification.getConfidence()).isNull();
        });
    }

    @Test
    void logsAFailedAssessmentAndCarriesOnWithTheNextUser(CapturedOutput output) {
        User first = user(7L, "first@example.com");
        first.setProgramEndDate(LocalDate.parse("2028-05-31"));
        User second = user(8L, "second@example.com");
        second.setProgramEndDate(LocalDate.parse("2028-05-31"));
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1")))
                .thenReturn(List.of(first, second));
        when(impactAssessor.assess(eq(first), any(), any()))
                .thenThrow(new IllegalStateException("model returned an empty generation"));
        when(impactAssessor.assess(eq(second), any(), any()))
                .thenReturn(Optional.of(new ImpactAssessment(ImpactSeverity.WARNING,
                        "Your program end date may be affected.", Confidence.LOW)));

        service().onPolicyIndexed(ADMISSION_RULE);

        assertThat(output).contains("Impact assessment failed for policy 3 and user 7",
                "IllegalStateException: model returned an empty generation");
        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2);
        assertThat(saved.getValue().get(0).getSeverity()).isNull();
        assertThat(saved.getValue().get(0).getMessage()).contains("Action needed");
        assertThat(saved.getValue().get(1).getSeverity()).isEqualTo(ImpactSeverity.WARNING);
    }

    @Test
    void stopsWaitingOnTheModelForThisPolicyOnceItIsUnreachable(CapturedOutput output) {
        User first = user(7L, "first@example.com");
        first.setProgramEndDate(LocalDate.parse("2028-05-31"));
        User second = user(8L, "second@example.com");
        second.setProgramEndDate(LocalDate.parse("2028-05-31"));
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1")))
                .thenReturn(List.of(first, second));
        // How RestClient reports a read timeout: not as a ResourceAccessException.
        when(impactAssessor.assess(eq(first), any(), any())).thenThrow(new RestClientException(
                "Error while extracting response", new SocketTimeoutException("Read timed out")));

        service().onPolicyIndexed(ADMISSION_RULE);

        assertThat(output).contains("Impact model unreachable for policy 3 at user 7",
                "SocketTimeoutException: Read timed out");
        verify(impactAssessor, never()).assess(eq(second), any(), any());
        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2).allSatisfy(notification -> {
            assertThat(notification.getSeverity()).isNull();
            assertThat(notification.getMessage()).contains("Action needed");
        });
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
        return new NotificationService(notificationRepository, userRepository, new PolicyImpactRules(),
                impactAssessor);
    }

    private User user(Long id, String email) {
        User user = new User("Student", email, "hash", "F-1", "Master's", null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
