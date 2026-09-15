package mygrant.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.ingestion.dto.IngestionResponse;
import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyDocumentRepository;

@Service
public class PolicyIngestionService {

    private final PolicyDocumentRepository policyRepository;
    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;

    public PolicyIngestionService(PolicyDocumentRepository policyRepository, VectorStore vectorStore) {
        this.policyRepository = policyRepository;
        this.vectorStore = vectorStore;
        this.textSplitter = new TokenTextSplitter();
    }

    @Transactional
    public IngestionResponse ingest(IngestPolicyRequest request) {
        String contentHash = calculateHash(request.content());
        var existing = policyRepository.findByExternalId(request.externalId());

        if (existing.isPresent()) {
            PolicyDocument document = existing.get();
            if (document.getContentHash().equals(contentHash) && document.getIndexedAt() != null) {
                return new IngestionResponse(document.getId(), document.getExternalId(), 0,
                        document.getIndexedAt(), true);
            }
            throw new PolicyAlreadyExistsException(request.externalId());
        }

        PolicyDocument policy = new PolicyDocument(
                request.externalId(), request.title(), request.agency(), normalizeVisaType(request.visaType()),
                request.status(), request.sourceType(), request.publicationDate(), request.effectiveDate(),
                request.sourceUrl(), request.content(), contentHash);

        PolicyDocument saved = policyRepository.save(policy);
        Document source = new Document(saved.getExternalId(), saved.getContent(), createMetadata(saved));
        List<Document> chunks = textSplitter.apply(List.of(source));

        vectorStore.add(chunks);

        Instant indexedAt = Instant.now();
        saved.markIndexed(indexedAt);
        policyRepository.save(saved);

        return new IngestionResponse(saved.getId(), saved.getExternalId(), chunks.size(), indexedAt, false);
    }

    private Map<String, Object> createMetadata(PolicyDocument policy) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("policyId", policy.getId().toString());
        metadata.put("externalId", policy.getExternalId());
        metadata.put("title", policy.getTitle());
        metadata.put("agency", policy.getAgency());
        metadata.put("visaType", policy.getVisaType());
        metadata.put("status", policy.getStatus().name());
        metadata.put("sourceType", policy.getSourceType().name());
        metadata.put("publicationDate", policy.getPublicationDate().toString());
        metadata.put("sourceUrl", policy.getSourceUrl());
        if (policy.getEffectiveDate() != null) {
            metadata.put("effectiveDate", policy.getEffectiveDate().toString());
        }
        return metadata;
    }

    private String normalizeVisaType(String visaType) {
        return visaType.trim().toUpperCase();
    }

    private String calculateHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
