package mygrant.impact;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import mygrant.ingestion.classification.VisaClassification;
import mygrant.ingestion.federalregister.FederalRegisterDocument;
import mygrant.user.User;
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;
import mygrant.user.UserRepository;

/** Uses Groq structured output to assess a newly imported policy for the signed-in user. */
@Service
public class ProfileImpactAssessmentService {

    private static final int MAX_POLICY_CHARACTERS = 12_000;

    private final RestClient groqClient;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final String apiKey;
    private final String model;

    public ProfileImpactAssessmentService(RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper, UserRepository userRepository,
            UserProfileRepository profileRepository,
            @Value("${groq.api-key:}") String apiKey,
            @Value("${groq.model:openai/gpt-oss-20b}") String model,
            @Value("${groq.base-url:https://api.groq.com/openai/v1}") String baseUrl) {
        this.groqClient = restClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
    }

    /** Assesses urgency without exposing the user's identity to the model. */
    public ProfileImpactAssessment assess(String email, FederalRegisterDocument document,
            String agency, String content, VisaClassification classification) {
        if (apiKey.isBlank()) {
            return unavailable("Add GROQ_API_KEY to .env to enable profile-specific urgency assessment.");
        }

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return unavailable("The signed-in user profile could not be loaded.");
        }
        UserProfile profile = profileRepository.findById(user.getId())
                .orElseGet(() -> UserProfile.fromUser(user));

        try {
            Map<String, Object> request = Map.of(
                    "model", model,
                    "temperature", 0,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt()),
                            Map.of("role", "user", "content",
                                    buildPrompt(profile, document, agency, content, classification))),
                    "response_format", responseFormat());

            JsonNode response = groqClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            String json = response.path("choices").path(0).path("message").path("content").asText();
            RawAssessment raw = objectMapper.readValue(json, RawAssessment.class);
            ImpactUrgency urgency = ImpactUrgency.valueOf(raw.urgency());
            return new ProfileImpactAssessment(urgency,
                    Math.max(0, Math.min(100, raw.confidence())), raw.summary(),
                    safeList(raw.reasons()), safeList(raw.recommendedActions()),
                    raw.requiresHumanReview(), "Groq", model);
        } catch (Exception exception) {
            return unavailable("Urgency assessment is temporarily unavailable; the source was still imported successfully.");
        }
    }

    private String systemPrompt() {
        return """
                You triage US immigration policy for one user. Use only the supplied policy and profile facts.
                HIGH means a direct status, work, travel, filing, or deadline risk needing prompt action.
                MEDIUM means likely relevant but not immediately time-critical, or important facts are uncertain.
                LOW means relevant information with no near-term action. NOT_APPLICABLE means no material profile match.
                Do not give legal advice or invent requirements. Make actions specific, cautious, and source-oriented.
                Set requiresHumanReview true for HIGH urgency or material uncertainty.
                """;
    }

    private String buildPrompt(UserProfile profile, FederalRegisterDocument document,
            String agency, String content, VisaClassification classification) {
        String excerpt = content == null ? "" : content.substring(0,
                Math.min(content.length(), MAX_POLICY_CHARACTERS));
        return """
                Assessment date: %s
                USER PROFILE (identity omitted)
                visaType: %s
                countryOfOrigin: %s
                countryOfCitizenship: %s
                currentLocation: %s
                employmentStatus: %s
                employer: %s
                visaStartDate: %s
                visaExpiryDate: %s
                optStartDate: %s
                i140FilingDate: %s
                priorityDate: %s
                academicLevel: %s
                programEndDate: %s
                upcomingTravelDate: %s

                POLICY
                documentNumber: %s
                title: %s
                agency: %s
                publicationDate: %s
                effectiveDate: %s
                deterministicVisaMatches: %s
                classifierSignals: %s
                contentExcerpt:
                %s
                """.formatted(LocalDate.now(), value(profile.getVisaType()),
                value(profile.getCountryOfOrigin()), value(profile.getCountryOfCitizenship()),
                value(profile.getCurrentLocation()), value(profile.getEmploymentStatus()),
                value(profile.getEmployer()), value(profile.getVisaStartDate()),
                value(profile.getVisaExpiryDate()), value(profile.getOptStartDate()),
                value(profile.getI140FilingDate()), value(profile.getPriorityDate()),
                value(profile.getAcademicLevel()), value(profile.getProgramEndDate()),
                value(profile.getUpcomingTravelDate()), value(document.documentNumber()),
                value(document.title()), value(agency), value(document.publicationDate()),
                value(document.effectiveDate()), classification.visaTypes(),
                classification.matchedSignals(), excerpt);
    }

    private Map<String, Object> responseFormat() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "urgency", Map.of("type", "string", "enum", List.of("HIGH", "MEDIUM", "LOW", "NOT_APPLICABLE")),
                        "confidence", Map.of("type", "integer", "minimum", 0, "maximum", 100),
                        "summary", Map.of("type", "string"),
                        "reasons", Map.of("type", "array", "items", Map.of("type", "string")),
                        "recommendedActions", Map.of("type", "array", "items", Map.of("type", "string")),
                        "requiresHumanReview", Map.of("type", "boolean")),
                "required", List.of("urgency", "confidence", "summary", "reasons",
                        "recommendedActions", "requiresHumanReview"),
                "additionalProperties", false);
        return Map.of("type", "json_schema", "json_schema", Map.of(
                "name", "profile_impact_assessment", "strict", true, "schema", schema));
    }

    private ProfileImpactAssessment unavailable(String summary) {
        return new ProfileImpactAssessment(ImpactUrgency.UNAVAILABLE, 0, summary,
                List.of(), List.of(), true, "Groq", model);
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private String value(Object value) {
        return value == null || value.toString().isBlank() ? "not provided" : value.toString();
    }

    private record RawAssessment(String urgency, int confidence, String summary,
            List<String> reasons, List<String> recommendedActions,
            boolean requiresHumanReview) {
    }
}
