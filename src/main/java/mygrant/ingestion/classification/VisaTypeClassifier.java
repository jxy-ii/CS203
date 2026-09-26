package mygrant.ingestion.classification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Applies explainable keyword rules to identify supported visa categories.
 * Explicit matches in a policy title take priority over incidental mentions in its body.
 */
@Component
public class VisaTypeClassifier {

    private static final Map<String, List<String>> SIGNALS = new LinkedHashMap<>();

    static {
        SIGNALS.put("F-1", List.of(
                "f-1", "f\u20131", "academic student", "optional practical training",
                "stem opt", "form i-20", "designated school official", "sevis"));
        SIGNALS.put("J-1", List.of(
                "j-1", "j\u20131", "exchange visitor", "form ds-2019",
                "responsible officer"));
        SIGNALS.put("H-1B", List.of(
                "h-1b", "h\u20131b", "specialty occupation", "labor condition application",
                "h-1b cap"));
    }

    /**
     * Classifies a document using substring signals in its title or, if the title has
     * no supported signal, its full text. This is a heuristic, not a legal determination.
     *
     * @param title published document title
     * @param content downloaded full text
     * @return supported visa categories and the signals that matched
     */
    public VisaClassification classify(String title, String content) {
        String normalizedTitle = title.toLowerCase(Locale.ROOT);
        String searchable = content.toLowerCase(Locale.ROOT);
        // A Federal Register rule may mention other visa programs only in
        // comparisons or background. When its title names supported categories,
        // use those explicit signals instead of incidental full-text mentions.
        boolean titleHasSignal = SIGNALS.values().stream()
                .flatMap(List::stream).anyMatch(normalizedTitle::contains);
        if (titleHasSignal) {
            searchable = normalizedTitle;
        }
        String textToClassify = searchable;
        List<String> visaTypes = new ArrayList<>();
        List<String> matchedSignals = new ArrayList<>();

        SIGNALS.forEach((visaType, signals) -> {
            List<String> matches = signals.stream().filter(textToClassify::contains).toList();
            if (!matches.isEmpty()) {
                visaTypes.add(visaType);
                matches.forEach(signal -> matchedSignals.add(visaType + ":" + signal));
            }
        });

        return new VisaClassification(
                visaTypes.contains("F-1"),
                visaTypes.contains("J-1"),
                visaTypes.contains("H-1B"),
                List.copyOf(visaTypes),
                List.copyOf(matchedSignals));
    }

}
