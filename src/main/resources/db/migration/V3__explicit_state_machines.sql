-- Explicit payment state machine: FAILED is now reachable and every state has
-- a timestamp invariant enforced by the database.
ALTER TABLE payments
    ADD COLUMN failed_at TIMESTAMPTZ;

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_status
        CHECK (status IN ('INITIATED', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'CANCELLED', 'REFUNDED'));

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_lifecycle_timestamps
        CHECK (
            (status = 'INITIATED' AND authorized_at IS NULL AND captured_at IS NULL
                AND refunded_at IS NULL AND cancelled_at IS NULL AND failed_at IS NULL)
            OR (status = 'AUTHORIZED' AND authorized_at IS NOT NULL AND captured_at IS NULL
                AND refunded_at IS NULL AND cancelled_at IS NULL AND failed_at IS NULL)
            OR (status = 'CAPTURED' AND authorized_at IS NOT NULL AND captured_at IS NOT NULL
                AND refunded_at IS NULL AND cancelled_at IS NULL AND failed_at IS NULL)
            OR (status = 'REFUNDED' AND authorized_at IS NOT NULL AND captured_at IS NOT NULL
                AND refunded_at IS NOT NULL AND cancelled_at IS NULL AND failed_at IS NULL)
            OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND captured_at IS NULL
                AND refunded_at IS NULL AND failed_at IS NULL)
            OR (status = 'FAILED' AND failed_at IS NOT NULL AND captured_at IS NULL
                AND refunded_at IS NULL AND cancelled_at IS NULL)
        );

-- Explicit audit event/status domains.
ALTER TABLE payment_audit_logs
    ADD CONSTRAINT ck_payment_audit_event_type
        CHECK (event_type IN ('PAYMENT_CREATED', 'PAYMENT_STATUS_CHANGED'));

ALTER TABLE payment_audit_logs
    ADD CONSTRAINT ck_payment_audit_from_status
        CHECK (from_status IS NULL
            OR from_status IN ('INITIATED', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'CANCELLED', 'REFUNDED'));

ALTER TABLE payment_audit_logs
    ADD CONSTRAINT ck_payment_audit_to_status
        CHECK (to_status IN ('INITIATED', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'CANCELLED', 'REFUNDED'));

-- Explicit idempotency registry. The old key columns on payments are moved here
-- so the payment aggregate no longer owns idempotency state.
CREATE TABLE idempotency_keys (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    payment_id UUID NOT NULL,
    response_status INT NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_idempotency_keys_key UNIQUE (idempotency_key),
    CONSTRAINT fk_idempotency_keys_payment
        FOREIGN KEY (payment_id) REFERENCES payments (id)
);

INSERT INTO idempotency_keys (id, idempotency_key, request_hash, payment_id, response_status, response_body,
                              created_at)
SELECT gen_random_uuid(),
       p.idempotency_key,
       p.request_hash,
       p.id,
       201,
       jsonb_build_object(
           'id', p.id,
           'status', p.status,
           'amount', p.amount,
           'currency', p.currency,
           'merchantReference', p.merchant_reference,
           'description', p.description,
           'version', p.version,
           'createdAt', p.created_at,
           'updatedAt', p.updated_at,
           'authorizedAt', p.authorized_at,
           'capturedAt', p.captured_at,
           'refundedAt', p.refunded_at,
           'cancelledAt', p.cancelled_at,
           'failedAt', p.failed_at
       )::text,
       p.created_at
FROM payments p;

ALTER TABLE payments DROP CONSTRAINT uq_payments_idempotency_key;
ALTER TABLE payments DROP COLUMN idempotency_key;
ALTER TABLE payments DROP COLUMN request_hash;

-- Explicit transactional outbox state machine.
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    exchange VARCHAR(255) NOT NULL,
    routing_key VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_outbox_events_status
        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX idx_outbox_events_due
    ON outbox_events (status, next_attempt_at);
