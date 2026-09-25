package mygrant.notifications;

import mygrant.common.Confidence;

/** The model's judgement of one policy for one applicant, accepted only when every field was valid. */
public record ImpactAssessment(ImpactSeverity severity, String explanation, Confidence confidence) {
}
