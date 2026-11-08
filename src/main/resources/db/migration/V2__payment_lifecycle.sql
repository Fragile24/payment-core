ALTER TABLE payments
    ADD COLUMN authorized_at TIMESTAMPTZ,
    ADD COLUMN captured_at TIMESTAMPTZ,
    ADD COLUMN refunded_at TIMESTAMPTZ,
    ADD COLUMN cancelled_at TIMESTAMPTZ;

CREATE INDEX idx_payments_status ON payments (status);
