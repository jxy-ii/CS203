package mygrant.notifications;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * Explainable keyword rules linking policy text to the profile fields it affects.
 * This is a heuristic to prompt a review, not a legal determination.
 */
@Component
public class PolicyImpactRules {

    // Federal Register raw text is hard-wrapped, so a signal can arrive split over two
    // lines as "duration of \nstatus". Runs of whitespace collapse to a single space
    // before matching, otherwise multi-word signals never match a downloaded rule.
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // Rules are scoped by visa type because one policy can be classified into several
    // categories. Without the scope, an H-1B reader would be told to review a field
    // that only the F-1 half of the policy touches.
    private static final List<Rule> RULES = List.of(
            Rule.of(ProfileField.PROGRAM_END_DATE, Set.of("F-1", "J-1"),
                    "period of admission", "duration of status", "date certain",
                    "program end date", "grace period"),
            // Practical training moves the work-authorization dates that follow a program
            // end date. It says nothing about which degree the student is enrolled in, so
            // it deliberately does not flag the academic level as well.
            Rule.of(ProfileField.PROGRAM_END_DATE, Set.of("F-1"),
                    "practical training", "stem opt"),
            Rule.of(ProfileField.ACADEMIC_LEVEL, Set.of("F-1", "J-1"),
                    "educational level", "academic level", "degree level", "change of program"));

    /**
     * Returns the affected fields for each visa type the policy was classified into,
     * reading the policy text once for all of them.
     *
     * @param visaTypes the visa categories the policy was classified into
     * @param title published policy title
     * @param content indexed policy excerpt, which may be null
     * @return affected fields keyed by normalized visa type
     */
    public Map<String, Set<ProfileField>> affectedFieldsByVisaType(Collection<String> visaTypes,
            String title, String content) {
        String searchable = WHITESPACE.matcher(title + " " + (content == null ? "" : content))
                .replaceAll(" ").toLowerCase(Locale.ROOT);

        return visaTypes.stream()
                .map(PolicyImpactRules::normalizeVisaType)
                .distinct()
                .collect(Collectors.toMap(visaType -> visaType,
                        visaType -> matchingFields(visaType, searchable)));
    }

    /** Compares visa types written with stray case or spacing as equal, such as {@code " f-1 "}. */
    public static String normalizeVisaType(String visaType) {
        return visaType == null ? "" : visaType.trim().toUpperCase(Locale.ROOT);
    }

    private Set<ProfileField> matchingFields(String visaType, String searchable) {
        Set<ProfileField> fields = EnumSet.noneOf(ProfileField.class);
        RULES.stream()
                .filter(rule -> rule.matches(visaType, searchable))
                .forEach(rule -> fields.add(rule.field()));
        return fields;
    }

    /** One field, the visa types it applies to, and the signals that trigger it. */
    private record Rule(ProfileField field, Set<String> visaTypes, List<Pattern> signals) {

        // Signals match on word boundaries so that an unrelated word ending in a signal,
        // such as "candidate certain", does not trigger a review.
        static Rule of(ProfileField field, Set<String> visaTypes, String... signals) {
            return new Rule(field, visaTypes, Arrays.stream(signals)
                    .map(signal -> Pattern.compile("\\b" + Pattern.quote(signal) + "\\b"))
                    .toList());
        }

        boolean matches(String visaType, String text) {
            return visaTypes.contains(visaType)
                    && signals.stream().anyMatch(signal -> signal.matcher(text).find());
        }
    }
}
