ALTER TABLE outbox_event ADD COLUMN claimed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE outbox_event DROP CONSTRAINT chk_outbox_event_status;
ALTER TABLE outbox_event ADD CONSTRAINT chk_outbox_event_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED'));

CREATE INDEX idx_outbox_event_pending_created_at
    ON outbox_event (created_at, id)
    WHERE status = 'PENDING';
