package mygrant.ingestion.federalregister;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** Retrieves Federal Register metadata and full text from the public API. */
@Component
public class FederalRegisterClient {

    private final RestClient apiClient;
    private final RestClient downloadClient;

    public FederalRegisterClient(RestClient.Builder builder) {
        this.apiClient = builder.clone()
                .baseUrl("https://www.federalregister.gov/api/v1")
                .build();
        this.downloadClient = builder.clone().build();
    }

    /** Fetches published metadata for a Federal Register document number. */
    public FederalRegisterDocument getDocument(String documentNumber) {
        return apiClient.get()
                .uri("/documents/{documentNumber}.json", documentNumber)
                .retrieve()
                .body(FederalRegisterDocument.class);
    }

    /** Downloads source text and removes NUL characters PostgreSQL cannot store. */
    public String downloadFullText(String rawTextUrl) {
        if (!StringUtils.hasText(rawTextUrl)) {
            throw new IllegalArgumentException("Federal Register document has no raw text URL");
        }
        String content = downloadClient.get().uri(rawTextUrl).retrieve().body(String.class);
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("Federal Register document returned empty full text");
        }
        // PostgreSQL TEXT cannot contain NUL bytes. Some Federal Register raw-text
        // documents include them, so remove only that invalid character.
        return content.replace("\u0000", "");
    }
}
