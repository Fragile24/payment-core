-- Reconciliation audit trail and the distributed lock that keeps two instances
-- from running the same sweep concurrently.

CREATE TABLE reconciliation_runs (
    id UUID PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL,
    payments_checked BIGINT NOT NULL DEFAULT 0,
    mismatches BIGINT NOT NULL DEFAULT 0,
    repaired BIGINT NOT NULL DEFAULT 0,
    orphan_projections BIGINT NOT NULL DEFAULT 0,
    outbox_pending BIGINT NOT NULL DEFAULT 0,
    outbox_stuck BIGINT NOT NULL DEFAULT 0,
    outbox_failed BIGINT NOT NULL DEFAULT 0,
    error TEXT,
    CONSTRAINT ck_reconciliation_runs_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE TABLE reconciliation_findings (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    type VARCHAR(32) NOT NULL,
    payment_id UUID,
    expected_status VARCHAR(32),
    actual_status VARCHAR(32),
    details TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_reconciliation_findings_run
        FOREIGN KEY (run_id) REFERENCES reconciliation_runs (id) ON DELETE CASCADE
);

CREATE INDEX idx_reconciliation_findings_run ON reconciliation_findings (run_id);

-- ShedLock-style lease. The UPDATE ... WHERE locked_until < now() is atomic, so
-- exactly one instance wins the lease; crashed instances let it expire.
CREATE TABLE scheduled_job_lock (
    job_name VARCHAR(128) PRIMARY KEY,
    locked_until TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(128) NOT NULL
);

INSERT INTO scheduled_job_lock (job_name, locked_until, locked_by)
VALUES ('payment-reconciliation', now() - INTERVAL '1 day', 'bootstrap');
