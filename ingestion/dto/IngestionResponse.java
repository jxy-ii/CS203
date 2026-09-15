package mygrant.ingestion.dto;

import java.time.Instant;

public record IngestionResponse(
        Long policyId,
        String externalId,
        int chunksIndexed,
        Instant indexedAt,
        boolean alreadyIndexed
) {
}
