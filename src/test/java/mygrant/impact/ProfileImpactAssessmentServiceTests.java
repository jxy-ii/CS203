package mygrant.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import mygrant.ingestion.classification.VisaClassification;
import mygrant.ingestion.federalregister.FederalRegisterDocument;
import mygrant.user.User;
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;
import mygrant.user.UserRepository;

class ProfileImpactAssessmentServiceTests {

    @Test
    void reportsUnavailableWhenGroqIsNotConfigured() {
        ProfileImpactAssessmentService service = new ProfileImpactAssessmentService(
                RestClient.builder(), new ObjectMapper(), mock(UserRepository.class),
                mock(UserProfileRepository.class), "", "test-model", "https://example.test");

        ProfileImpactAssessment result = service.assess("student@example.com", document(),
                "DHS", "policy content", classification());

        assertThat(result.urgency()).isEqualTo(ImpactUrgency.UNAVAILABLE);
        assertThat(result.summary()).contains("GROQ_API_KEY");
    }

    @Test
    void parsesStrictStructuredAssessmentFromGroq() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UserRepository users = mock(UserRepository.class);
        UserProfileRepository profiles = mock(UserProfileRepository.class);
        User user = new User();
        user.setId(7L);
        user.setEmail("student@example.com");
        user.setVisaType("F-1");
        UserProfile profile = UserProfile.fromUser(user);
        profile.setUpcomingTravelDate(LocalDate.of(2026, 10, 1));
        when(users.findByEmail("student@example.com")).thenReturn(Optional.of(user));
        when(profiles.findById(7L)).thenReturn(Optional.of(profile));

        server.expect(requestTo("https://api.groq.test/chat/completions"))
                .andExpect(header("Authorization", "Bearer secret"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"{\\"urgency\\":\\"HIGH\\",\\"confidence\\":92,\\"summary\\":\\"Travel may be affected.\\",\\"reasons\\":[\\"Upcoming travel overlaps the effective period.\\"],\\"recommendedActions\\":[\\"Review the primary source.\\"],\\"requiresHumanReview\\":true}"}}]}
                        """, MediaType.APPLICATION_JSON));

        ProfileImpactAssessmentService service = new ProfileImpactAssessmentService(builder,
                new ObjectMapper(), users, profiles, "secret", "test-model", "https://api.groq.test");
        ProfileImpactAssessment result = service.assess("student@example.com", document(),
                "DHS", "policy content", classification());

        assertThat(result.urgency()).isEqualTo(ImpactUrgency.HIGH);
        assertThat(result.confidence()).isEqualTo(92);
        assertThat(result.recommendedActions()).containsExactly("Review the primary source.");
        assertThat(result.provider()).isEqualTo("Groq");
        server.verify();
    }

    private FederalRegisterDocument document() {
        return new FederalRegisterDocument("2026-14439", "Fixed admission period", "Rule",
                LocalDate.of(2026, 8, 27), LocalDate.of(2026, 9, 28),
                "https://example.test/document", "https://example.test/document.txt",
                List.of(new FederalRegisterDocument.Agency("DHS", "dhs")));
    }

    private VisaClassification classification() {
        return new VisaClassification(true, true, false, List.of("F-1", "J-1"),
                List.of("F-1:academic student"));
    }
}
