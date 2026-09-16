package mygrant.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import mygrant.ingestion.classification.VisaTypeClassifier;

/** Checks title-priority classification and unrelated-document rejection. */
class VisaTypeClassifierTests {

    private final VisaTypeClassifier classifier = new VisaTypeClassifier();

    @Test
    void detectsF1AndJ1FromPolicyLanguage() {
        var result = classifier.classify("Academic student and exchange visitor admission",
                "This rule applies to F-1 academic students and J-1 exchange visitors.");

        assertThat(result.affectsF1()).isTrue();
        assertThat(result.affectsJ1()).isTrue();
        assertThat(result.affectsH1b()).isFalse();
        assertThat(result.visaTypes()).containsExactly("F-1", "J-1");
    }

    @Test
    void doesNotClassifyUnrelatedContent() {
        var result = classifier.classify("Agricultural reporting", "Quarterly crop report.");
        assertThat(result.visaTypes()).isEmpty();
    }

    @Test
    void ignoresIncidentalOtherVisasWhenTitleNamesAffectedCategories() {
        var result = classifier.classify(
                "Admission for Nonimmigrant Academic Students and Exchange Visitors",
                "The discussion compares F-1 and J-1 with H-1B specialty occupation workers.");

        assertThat(result.visaTypes()).containsExactly("F-1", "J-1");
        assertThat(result.affectsH1b()).isFalse();
    }

}
