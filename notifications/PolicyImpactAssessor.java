package mygrant.notifications;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import mygrant.common.Confidence;
import mygrant.policies.PolicyIndexedEvent;
import mygrant.user.User;

/**
 * Asks the chat model how urgently a policy affects one applicant the keyword rules flagged.
 * The rules can tell that a policy touches someone's program end date, but not that the same
 * change is routine for a student who stays in the US and urgent for one who re-enters after
 * it takes effect. This adds that judgement on top of the rules, never in place of them: it is
 * only asked about users the rules already flagged, and an unusable answer leaves the
 * rule-based alert as it was. Its output is advisory, not a legal determination.
 */
@Service
public class PolicyImpactAssessor {

    private static final Logger log = LoggerFactory.getLogger(PolicyImpactAssessor.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // llama3.2 runs with a small context window by default. A long Federal Register excerpt
    // would otherwise push the instructions out of context and the model would ignore them.
    private static final int EXCERPT_LIMIT = 3000;
    private static final int EXPLANATION_LIMIT = 500;

    // Even in JSON mode, small models sometimes wrap the object in a code fence or a sentence.
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*\\}", Pattern.DOTALL);

    private static final String SYSTEM_PROMPT = """
            You are myGRANT's policy impact assessor. You judge how urgently one new
            immigration policy affects one applicant, using only the policy text and
            profile facts supplied. This is not legal advice.

            Severity:
            - CRITICAL: likely to affect the applicant's status, work authorization or
              re-entry around the policy's effective date; they should act now.
            - WARNING: plausibly affects a profile fact they hold; they should review it.
            - INFORMATIONAL: relevant to their visa category but unlikely to change
              anything for this applicant.
            - NO_IMPACT: the supplied facts show the policy does not apply to them.

            Rules:
            - Never invent dates, requirements, exceptions or legal outcomes.
            - If the upcoming travel date is not on file, you do not know whether the
              applicant will travel, even if their current location is known. Do not
              assume they are staying in the US, and do not assume they are traveling.
              Treat it as unknown, and reflect that uncertainty: confidence should
              generally not be HIGH for any severity above NO_IMPACT.
            - The explanation is at most 2 sentences of plain English addressed to the
              applicant as "you", naming the profile fact at risk. Do not cite
              regulation numbers.

            Respond with only this JSON object and nothing else:
            {"severity": "CRITICAL|WARNING|INFORMATIONAL|NO_IMPACT", "explanation": "...", "confidence": "HIGH|MEDIUM|LOW"}
            """;

    private final ChatClient chatClient;

    public PolicyImpactAssessor(@Qualifier("impactAssessmentChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Rates the policy for one applicant. A model answer missing any field, or using a value
     * outside the allowed set, is rejected whole rather than partly used. Failures to reach
     * the model are thrown, so the caller can decide whether to keep trying other users.
     *
     * @param user the applicant the rules flagged
     * @param event the newly indexed policy
     * @param flaggedFields the profile fields the rules flagged that the user has filled in
     * @return the assessment, or empty when the model's answer was unusable
     */
    public Optional<ImpactAssessment> assess(User user, PolicyIndexedEvent event, List<ProfileField> flaggedFields) {
        String content = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt(user, event, flaggedFields))
                .call()
                .content();

        Optional<ImpactAssessment> assessment = parse(content);
        if (assessment.isEmpty()) {
            log.warn("Unusable impact assessment for policy {} and user {}: {}",
                    event.policyId(), user.getId(), abbreviate(content, 200));
        }
        return assessment;
    }

    private String userPrompt(User user, PolicyIndexedEvent event, List<ProfileField> flaggedFields) {
        StringBuilder profile = new StringBuilder("- Visa type: " + user.getVisaType().trim() + "\n");
        for (ProfileField field : flaggedFields) {
            profile.append("- ").append(capitalize(field.label())).append(": ")
                    .append(profileValue(field, user)).append("\n");
        }

        return """
                Today's date: %s

                Policy title: %s
                Policy effective date: %s
                Policy excerpt:
                <<<
                %s
                >>>

                Applicant profile:
                %sProfile fields flagged by keyword rules: %s

                %s
                """.formatted(
                LocalDate.now(),
                event.title(),
                event.effectiveDate() == null ? "not stated" : event.effectiveDate(),
                event.indexedContent() == null ? "(no excerpt available)"
                        : abbreviate(event.indexedContent().trim(), EXCERPT_LIMIT),
                profile,
                flaggedFields.stream().map(ProfileField::label).collect(Collectors.joining(", ")),
                travelContext(user));
    }

    // Unknown travel is stated as unknown. Saying nothing, or "not traveling", would let the
    // model rate a student as safe to stay put when we simply do not know their plans.
    private String travelContext(User user) {
        String location = user.getCurrentLocation() == null || user.getCurrentLocation().isBlank()
                ? null : user.getCurrentLocation().trim();
        LocalDate travelDate = user.getUpcomingTravelDate();
        String context = location == null && travelDate == null
                ? "Travel: no travel information on file. Current location and upcoming "
                        + "travel date are both unknown."
                : "Travel:\n- Current location: " + (location == null ? "not on file" : location)
                        + "\n- Upcoming travel date: " + (travelDate == null ? "not on file" : travelDate);
        // Small models follow a rule stated beside the data more reliably than one stated
        // only in the system prompt, so the uncertainty guidance is repeated here.
        return travelDate == null
                ? context + "\nWhether the applicant will travel is unknown, so confidence should "
                        + "not be HIGH unless severity is NO_IMPACT."
                : context;
    }

    private String profileValue(ProfileField field, User user) {
        return switch (field) {
            case PROGRAM_END_DATE -> String.valueOf(user.getProgramEndDate());
            case ACADEMIC_LEVEL -> user.getAcademicLevel().trim();
        };
    }

    private Optional<ImpactAssessment> parse(String content) {
        if (content == null) {
            return Optional.empty();
        }
        Matcher json = JSON_OBJECT.matcher(content);
        if (!json.find()) {
            return Optional.empty();
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json.group());
            ImpactSeverity severity = enumValue(ImpactSeverity.class, node.path("severity"));
            Confidence confidence = enumValue(Confidence.class, node.path("confidence"));
            String explanation = node.path("explanation").isTextual()
                    ? node.path("explanation").asText().trim() : "";
            if (severity == null || confidence == null || explanation.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new ImpactAssessment(severity, abbreviate(explanation, EXPLANATION_LIMIT),
                    confidence));
        } catch (JsonProcessingException exception) {
            return Optional.empty();
        }
    }

    /** Reads "no impact" or "No-Impact" as NO_IMPACT; anything outside the enum is null. */
    private static <E extends Enum<E>> E enumValue(Class<E> type, JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        String name = node.asText().trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(name)) {
                return constant;
            }
        }
        return null;
    }

    private static String capitalize(String label) {
        return Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    private static String abbreviate(String text, int limit) {
        if (text == null) {
            return null;
        }
        return text.length() > limit ? text.substring(0, limit - 3) + "..." : text;
    }
}
