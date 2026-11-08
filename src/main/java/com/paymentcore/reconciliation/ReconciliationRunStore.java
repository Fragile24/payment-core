package com.paymentcore.reconciliation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Persists reconciliation runs and findings.
 *
 * <p>The run row is committed before the sweep starts (so a crash still leaves a
 * visible {@code RUNNING} run for forensics) and updated afterwards. Findings
 * are written with the completion update in one transaction: a run is either
 * fully reported or not reported at all.</p>
 */
@Repository
public class ReconciliationRunStore {

	private final JdbcTemplate jdbc;

	public ReconciliationRunStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public UUID start() {
		UUID runId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO reconciliation_runs (id, started_at, status)
				VALUES (?, now(), 'RUNNING')
				""", runId);
		return runId;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void complete(UUID runId, ReconciliationSummary summary) {
		jdbc.update("""
				UPDATE reconciliation_runs
				SET finished_at = now(), status = 'COMPLETED', payments_checked = ?, mismatches = ?,
				    repaired = ?, orphan_projections = ?, outbox_pending = ?, outbox_stuck = ?,
				    outbox_failed = ?
				WHERE id = ?
				""", summary.paymentsChecked(), summary.mismatches(), summary.repaired(),
				summary.orphanProjections(), summary.outboxPending(), summary.outboxStuck(), summary.outboxFailed(),
				runId);
		for (ReconciliationFinding finding : summary.findings()) {
			jdbc.update("""
					INSERT INTO reconciliation_findings
					    (id, run_id, type, payment_id, expected_status, actual_status, details, created_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, now())
					""", UUID.randomUUID(), runId, finding.type().name(), finding.paymentId(),
					finding.expectedStatus(), finding.actualStatus(), finding.details());
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void fail(UUID runId, RuntimeException failure) {
		jdbc.update("""
				UPDATE reconciliation_runs
				SET finished_at = now(), status = 'FAILED', error = ?
				WHERE id = ?
				""", failure.getClass().getSimpleName() + ": " + failure.getMessage(), runId);
	}

}
