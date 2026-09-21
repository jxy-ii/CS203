CREATE TABLE notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    policy_id BIGINT NOT NULL REFERENCES policy_documents (id) ON DELETE CASCADE,
    message VARCHAR(1500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_notifications_user_policy UNIQUE (user_id, policy_id)
);

CREATE INDEX idx_notifications_user_created
    ON notifications (user_id, created_at DESC);
