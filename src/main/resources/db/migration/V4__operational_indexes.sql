-- Operational indexes.
--
-- These do not change the domain model; they keep production support queries and
-- future retention jobs cheap. They are versioned like any other schema change so
-- every environment applies them in the same order.

-- Retention: idempotency keys are useless after the client's retry window, so a
-- cleanup job will delete by age. Scanning by created_at must not be a seq scan.
CREATE INDEX idx_idempotency_keys_created_at ON idempotency_keys (created_at);

-- Alerting/forensics: list the oldest failed outbox events without touching the
-- pending backlog. status = 'FAILED' is a small, slowly growing subset.
CREATE INDEX idx_outbox_events_failed ON outbox_events (created_at) WHERE status = 'FAILED';
