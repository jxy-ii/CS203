package mygrant.policies;

import java.time.LocalDate;
import java.util.List;

/** Published once when a new policy has been stored and indexed, with the excerpt that was indexed. */
public record PolicyIndexedEvent(
        Long policyId,
        String title,
        List<String> visaTypes,
        LocalDate effectiveDate,
        String indexedContent
) {
}
