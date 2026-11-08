-- Read model built by the projection consumer.
--
-- It deliberately has no foreign key to payments: the projection is a consumer
-- view, and a consumer must be able to record an event even if the source row
-- is not visible from its own schema (and in a real deployment it may live in
-- another database). Reconciliation is what detects divergence.

CREATE TABLE payment_projection (
    payment_id UUID PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    version BIGINT NOT NULL,
    last_event_id UUID,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Delivery-level idempotency. The key is (delivery id, consumer name) so two
-- consumer groups can process the same event independently.
CREATE TABLE processed_events (
    event_id UUID NOT NULL,
    consumer VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_processed_events PRIMARY KEY (event_id, consumer)
);
