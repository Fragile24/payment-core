CREATE TABLE payments (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    merchant_reference VARCHAR(128),
    description VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_payments_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_payments_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_payments_created_at ON payments (created_at DESC);

CREATE TABLE payment_audit_logs (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    details TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_payment_audit_logs_payment
        FOREIGN KEY (payment_id) REFERENCES payments (id)
);

CREATE INDEX idx_payment_audit_logs_payment_id
    ON payment_audit_logs (payment_id, created_at);
