package com.paymentcore.reconciliation;

import com.paymentcore.TestcontainersConfiguration;
import com.paymentcore.payment.dto.CreatePaymentRequest;
import com.paymentcore.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = { "app.reconciliation.enabled=false", "app.ledger.consumer-enabled=false" })
@Import(TestcontainersConfiguration.class)
class ReconciliationIntegrationTest {

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private ReconciliationService reconciliationService;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void cleanState() {
		jdbc.update("DELETE FROM reconciliation_findings");
		jdbc.update("DELETE FROM reconciliation_runs");
		jdbc.update("DELETE FROM payment_projection");
		jdbc.update("DELETE FROM processed_events");
		jdbc.update("DELETE FROM payment_audit_logs");
		jdbc.update("DELETE FROM idempotency_keys");
		jdbc.update("DELETE FROM outbox_events");
		jdbc.update("DELETE FROM payments");
		jdbc.update(
				"UPDATE scheduled_job_lock SET locked_until = now() - INTERVAL '1 day' WHERE job_name = ?",
				ReconciliationService.JOB_NAME);
	}

	@Test
	void repairsMissingAndStaleProjectionsAndRecordsFindings() {
		UUID paymentId = paymentService.create("reconcile-0001", paymentRequest()).payment().id();
		UUID orphanId = UUID.randomUUID();
		insertProjection(orphanId, "INITIATED", new BigDecimal("1.00"), "USD", 0);
		insertFailedOutboxEvent(paymentId);

		assertThat(reconciliationService.reconcile()).isEqualTo(ReconciliationOutcome.COMPLETED);

		UUID firstRun = latestRunId();
		assertThat(projectionStatus(paymentId)).isEqualTo("INITIATED");
		assertThat(projectionVersion(paymentId)).isZero();
		assertThat(countFindings(firstRun, "MISSING_PROJECTION")).isEqualTo(1);
		assertThat(countFindings(firstRun, "ORPHAN_PROJECTION")).isEqualTo(1);
		assertThat(countFindings(firstRun, "OUTBOX_FAILED")).isEqualTo(1);
		assertThat(runLongColumn(firstRun, "repaired")).isEqualTo(1);
		assertThat(runLongColumn(firstRun, "mismatches")).isEqualTo(1);

		// Corrupt the projection: the next sweep must detect it as STALE and restore
		// the source state, including the version used for ordering.
		jdbc.update("UPDATE payment_projection SET status = 'CAPTURED', version = 99 WHERE payment_id = ?", paymentId);

		assertThat(reconciliationService.reconcile()).isEqualTo(ReconciliationOutcome.COMPLETED);

		UUID secondRun = latestRunId();
		assertThat(secondRun).isNotEqualTo(firstRun);
		assertThat(projectionStatus(paymentId)).isEqualTo("INITIATED");
		assertThat(projectionVersion(paymentId)).isZero();
		assertThat(countFindings(secondRun, "STALE_PROJECTION")).isEqualTo(1);

		// Third sweep: the payment is consistent again; only the deliberately
		// unrepairable findings remain.
		assertThat(reconciliationService.reconcile()).isEqualTo(ReconciliationOutcome.COMPLETED);

		UUID thirdRun = latestRunId();
		assertThat(countFindings(thirdRun, "STALE_PROJECTION")).isZero();
		assertThat(countFindings(thirdRun, "MISSING_PROJECTION")).isZero();
		assertThat(countFindings(thirdRun, "ORPHAN_PROJECTION")).isEqualTo(1);
		assertThat(runLongColumn(thirdRun, "repaired")).isZero();
	}

	@Test
	void skipsWhenAnotherInstanceHoldsTheLease() {
		jdbc.update("UPDATE scheduled_job_lock SET locked_until = now() + INTERVAL '1 hour' WHERE job_name = ?",
				ReconciliationService.JOB_NAME);
		long runsBefore = countRuns();

		assertThat(reconciliationService.reconcile()).isEqualTo(ReconciliationOutcome.SKIPPED);

		assertThat(countRuns()).isEqualTo(runsBefore);
	}

	private UUID latestRunId() {
		return jdbc.queryForObject(
				"SELECT id FROM reconciliation_runs ORDER BY started_at DESC LIMIT 1", UUID.class);
	}

	private long countRuns() {
		return jdbc.queryForObject("SELECT count(*) FROM reconciliation_runs", Long.class);
	}

	private void insertProjection(UUID paymentId, String status, BigDecimal amount, String currency, long version) {
		jdbc.update("""
				INSERT INTO payment_projection (payment_id, status, amount, currency, version, last_event_id, updated_at)
				VALUES (?, ?, ?, ?, ?, NULL, now())
				""", paymentId, status, amount, currency, version);
	}

	private void insertFailedOutboxEvent(UUID paymentId) {
		jdbc.update("""
				INSERT INTO outbox_events
				    (id, aggregate_type, aggregate_id, event_type, exchange, routing_key, payload, status,
				     attempts, next_attempt_at, last_error, created_at, published_at, version)
				VALUES (?, 'PAYMENT', ?, 'PAYMENT_CREATED', 'payment.events', 'payment.created', '{}',
				        'FAILED', 5, NULL, 'broker nack', now(), NULL, 0)
				""", UUID.randomUUID(), paymentId);
	}

	private String projectionStatus(UUID paymentId) {
		return jdbc.queryForObject("SELECT status FROM payment_projection WHERE payment_id = ?", String.class,
				paymentId);
	}

	private long projectionVersion(UUID paymentId) {
		return jdbc.queryForObject("SELECT version FROM payment_projection WHERE payment_id = ?", Long.class,
				paymentId);
	}

	private int countFindings(UUID runId, String type) {
		return jdbc.queryForObject(
				"SELECT count(*) FROM reconciliation_findings WHERE run_id = ? AND type = ?", Integer.class, runId,
				type);
	}

	private long runLongColumn(UUID runId, String column) {
		return jdbc.queryForObject("SELECT " + column + " FROM reconciliation_runs WHERE id = ?", Long.class,
				runId);
	}

	private CreatePaymentRequest paymentRequest() {
		return new CreatePaymentRequest(new BigDecimal("10.00"), "USD", "ORDER-RECONCILE-0001",
				"Reconciliation source");
	}

}
