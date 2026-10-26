ALTER TABLE outbox_event ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE;

-- The existing pending (created_at, id) index still supports oldest-first scans.
CREATE INDEX idx_outbox_event_processing_claimed_at
    ON outbox_event (claimed_at, id)
    WHERE status = 'PROCESSING';
