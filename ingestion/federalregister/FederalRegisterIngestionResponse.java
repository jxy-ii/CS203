package mygrant.ingestion.federalregister;

import java.util.List;

import mygrant.ingestion.dto.IngestionResponse;

/** Import result including explainable visa classification and indexing status. */
public record FederalRegisterIngestionResponse(
        String documentNumber,
        boolean affectsF1,
        boolean affectsJ1,
        boolean affectsH1b,
        List<String> detectedVisaTypes,
        List<String> matchedSignals,
        IngestionResponse ingestion
) {
}
