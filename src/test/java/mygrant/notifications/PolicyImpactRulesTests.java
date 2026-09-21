package mygrant.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Verifies the keyword rules that map policy text to affected profile fields. */
class PolicyImpactRulesTests {

    private final PolicyImpactRules rules = new PolicyImpactRules();

    @Test
    void fixedPeriodOfAdmissionAffectsProgramEndDate() {
        assertThat(fieldsFor("F-1", "Fixed Time Period of Admission for F-1 Students", ""))
                .containsExactly(ProfileField.PROGRAM_END_DATE);
    }

    @Test
    void practicalTrainingAffectsOnlyTheProgramEndDate() {
        assertThat(fieldsFor("F-1", "STEM OPT changes", "Optional Practical Training is revised."))
                .containsExactly(ProfileField.PROGRAM_END_DATE);
    }

    @Test
    void practicalTrainingDoesNotAffectOtherVisaCategories() {
        assertThat(fieldsFor("H-1B", "STEM OPT changes", "Optional Practical Training is revised."))
                .isEmpty();
        assertThat(fieldsFor("J-1", "STEM OPT changes", "Optional Practical Training is revised."))
                .isEmpty();
    }

    @Test
    void durationOfStatusAffectsStudentAndExchangeVisitorProgramDates() {
        assertThat(fieldsFor("J-1", "Duration of status is replaced", ""))
                .containsExactly(ProfileField.PROGRAM_END_DATE);
        assertThat(fieldsFor("H-1B", "Duration of status is replaced", "")).isEmpty();
    }

    @Test
    void degreeLevelAffectsAcademicLevel() {
        assertThat(fieldsFor("F-1", "Eligibility by degree level", ""))
                .containsExactly(ProfileField.ACADEMIC_LEVEL);
    }

    @Test
    void signalsMatchAcrossTheLineWrappingInDownloadedPolicyText() {
        // Federal Register raw text is hard-wrapped, so a signal arrives split over two lines.
        assertThat(fieldsFor("F-1", "Admission periods",
                "This rule ends duration of \nstatus for academic students."))
                .containsExactly(ProfileField.PROGRAM_END_DATE);
    }

    @Test
    void signalsMatchWholeWordsOnly() {
        assertThat(fieldsFor("F-1", "Candidate certain requirements", "")).isEmpty();
    }

    @Test
    void unrelatedPolicyAffectsNothing() {
        assertThat(fieldsFor("F-1", "Fee schedule update", "Adjusts filing fees.")).isEmpty();
        assertThat(fieldsFor("F-1", "Fee schedule update", null)).isEmpty();
    }

    @Test
    void unknownOrMissingVisaTypeAffectsNothing() {
        assertThat(fieldsFor("O-1", "Duration of status is replaced", "")).isEmpty();
        assertThat(fieldsFor(null, "Duration of status is replaced", "")).isEmpty();
    }

    @Test
    void visaTypeComparisonIgnoresCaseAndWhitespace() {
        assertThat(fieldsFor(" f-1 ", "Duration of status is replaced", ""))
                .containsExactly(ProfileField.PROGRAM_END_DATE);
    }

    /** Reads the rules for one visa type, since each case here describes a single reader. */
    private Set<ProfileField> fieldsFor(String visaType, String title, String content) {
        return rules.affectedFieldsByVisaType(Collections.singletonList(visaType), title, content)
                .get(PolicyImpactRules.normalizeVisaType(visaType));
    }
}
