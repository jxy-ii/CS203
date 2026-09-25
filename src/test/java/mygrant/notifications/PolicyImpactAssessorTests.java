package mygrant.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import mygrant.common.Confidence;
import mygrant.policies.PolicyIndexedEvent;
import mygrant.user.User;

/** Verifies what the impact model is told about an applicant and which of its answers are trusted. */
@ExtendWith(MockitoExtension.class)
class PolicyImpactAssessorTests {

    private static final PolicyIndexedEvent EVENT = new PolicyIndexedEvent(3L, "Fixed Time Period of Admission",
            List.of("F-1"), LocalDate.parse("2026-10-01"), "Ends duration of status.");

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ChatClient chatClient;

    @Test
    void parsesTheAssessmentAndSendsTheApplicantsTravelPlans() {
        User student = student();
        student.setCurrentLocation("US");
        student.setUpcomingTravelDate(LocalDate.parse("2026-12-15"));
        answer("""
                {"severity": "CRITICAL",
                 "explanation": "You plan to re-enter after the rule takes effect, so your program end date may no longer cover your stay.",
                 "confidence": "MEDIUM"}""");

        var assessment = assessor().assess(student, EVENT, List.of(ProfileField.PROGRAM_END_DATE));

        assertThat(assessment).hasValueSatisfying(result -> {
            assertThat(result.severity()).isEqualTo(ImpactSeverity.CRITICAL);
            assertThat(result.confidence()).isEqualTo(Confidence.MEDIUM);
            assertThat(result.explanation()).startsWith("You plan to re-enter");
        });
        assertThat(sentPrompt()).contains(
                "Policy effective date: 2026-10-01",
                "- Visa type: F-1",
                "- Program end date: 2028-05-31",
                "- Current location: US",
                "- Upcoming travel date: 2026-12-15");
    }

    @Test
    void tellsTheModelTravelIsUnknownRatherThanThatTheUserStaysPut() {
        User student = student(); // neither travel field filled in
        answer("""
                {"severity": "WARNING", "explanation": "Your program end date may be affected.", "confidence": "LOW"}""");

        var assessment = assessor().assess(student, EVENT, List.of(ProfileField.PROGRAM_END_DATE));

        assertThat(assessment).hasValueSatisfying(
                result -> assertThat(result.confidence()).isEqualTo(Confidence.LOW));
        assertThat(sentPrompt())
                .contains("no travel information on file")
                .contains("both unknown")
                .doesNotContainIgnoringCase("not traveling")
                .doesNotContainIgnoringCase("staying")
                .doesNotContain("Current location:");
    }

    @Test
    void marksOnlyTheMissingTravelFieldAsNotOnFile() {
        User student = student();
        student.setCurrentLocation("ABROAD");
        answer("""
                {"severity": "WARNING", "explanation": "Your program end date may be affected.", "confidence": "LOW"}""");

        assessor().assess(student, EVENT, List.of(ProfileField.PROGRAM_END_DATE));

        assertThat(sentPrompt()).contains("- Current location: ABROAD", "- Upcoming travel date: not on file");
    }

    @Test
    void sendsOnlyTheProfileFieldsTheRulesFlagged() {
        answer("""
                {"severity": "WARNING", "explanation": "Your program end date may be affected.", "confidence": "LOW"}""");

        assessor().assess(student(), EVENT, List.of(ProfileField.PROGRAM_END_DATE));

        assertThat(sentPrompt()).doesNotContain("Academic level", "Master's");
    }

    @Test
    void acceptsAnswersWrappedInACodeFenceOrWrittenInMixedCase() {
        answer("""
                Here is the assessment:
                ```json
                {"severity": "no impact", "explanation": "This rule does not change your dates.", "confidence": "high"}
                ```""");

        var assessment = assessor().assess(student(), EVENT, List.of(ProfileField.PROGRAM_END_DATE));

        assertThat(assessment).hasValueSatisfying(result -> {
            assertThat(result.severity()).isEqualTo(ImpactSeverity.NO_IMPACT);
            assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "This policy is critical for you.",
            "{\"severity\": \"CRITICAL\", \"explanation\": \"Your dates",
            "{\"severity\": \"URGENT\", \"explanation\": \"Your dates may change.\", \"confidence\": \"HIGH\"}",
            "{\"severity\": \"WARNING\", \"explanation\": \"Your dates may change.\"}",
            "{\"severity\": \"WARNING\", \"explanation\": \"  \", \"confidence\": \"LOW\"}",
            "{\"severity\": 2, \"explanation\": \"Your dates may change.\", \"confidence\": \"LOW\"}",
            ""})
    void rejectsAnAnswerThatIsNotCompleteValidJson(String modelOutput) {
        answer(modelOutput);

        assertThat(assessor().assess(student(), EVENT, List.of(ProfileField.PROGRAM_END_DATE))).isEmpty();
    }

    private void answer(String content) {
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content()).thenReturn(content);
    }

    private String sentPrompt() {
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        // Stubbing through deep stubs records a call too, so the last captured prompt is the real one.
        verify(chatClient.prompt().system(anyString()), atLeastOnce()).user(prompt.capture());
        return prompt.getValue();
    }

    private PolicyImpactAssessor assessor() {
        return new PolicyImpactAssessor(chatClient);
    }

    private User student() {
        User user = new User("Student", "student@example.com", "hash", "F-1", "Master's",
                LocalDate.parse("2028-05-31"));
        ReflectionTestUtils.setField(user, "id", 7L);
        return user;
    }
}
