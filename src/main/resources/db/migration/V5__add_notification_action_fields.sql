ALTER TABLE notifications
    ADD COLUMN action_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN affected_fields VARCHAR(255);
