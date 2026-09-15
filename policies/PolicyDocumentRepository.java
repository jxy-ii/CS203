package mygrant.policies;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, Long> {
    Optional<PolicyDocument> findByExternalId(String externalId);
}
