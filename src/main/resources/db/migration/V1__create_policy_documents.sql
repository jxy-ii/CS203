CREATE TABLE policy_documents (
    id BIGSERIAL PRIMARY KEY,
    external_id VARCHAR(255) NOT NULL UNIQUE,
    title VARCHAR(1000) NOT NULL,
    agency VARCHAR(255) NOT NULL,
    visa_type VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    publication_date DATE NOT NULL,
    effective_date DATE,
    source_url VARCHAR(2048) NOT NULL,
    content TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    indexed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_policy_documents_visa_type
    ON policy_documents (visa_type);

CREATE INDEX idx_policy_documents_status
    ON policy_documents (status);
