package com.paymentcore.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Compares the source of truth with the downstream read model and with the
 * outbox backlog, repairs what is safe to repair, and records the rest.
 *
 * <p>Two rules keep this safe to run at any time:</p>
 * <ul>
 *   <li>Only the projection is repaired. Payment rows and outbox rows are never
 *   rewritten: a reconciliation job that mutates the source of truth hides the
 *   bug that caused the drift.</li>
 *   <li>Each run is bounded by {@code app.reconciliation.batch-size}. Repeated
 *   runs converge, and a huge drift cannot exhaust memory or hold one long
 *   transaction.</li>
 * </ul>
 */
@Service
public class ReconciliationService {

	static final String JOB_NAME = "payment-reconciliation";

	private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

	private final ReconciliationStore store;

	private final ProjectionRepairer repairer;

	private final ReconciliationRunStore runStore;

	private final DatabaseJobLock jobLock;

	private final ReconciliationProperties properties;

	private final ReconciliationMetrics metrics;

	public ReconciliationService(ReconciliationStore store, ProjectionRepairer repairer,
			ReconciliationRunStore runStore, DatabaseJobLock jobLock, ReconciliationProperties properties,
			ReconciliationMetrics metrics) {
		this.store = store;
		this.repairer = repairer;
		this.runStore = runStore;
		this.jobLock = jobLock;
		this.properties = properties;
		this.metrics = metrics;
	}

	public ReconciliationOutcome reconcile() {
		String owner = UUID.randomUUID().toString();
		if (!jobLock.tryAcquire(JOB_NAME, owner, Instant.now().plus(properties.lockTtl()))) {
			metrics.recordSkipped();
			log.info("Reconciliation skipped: another instance holds job={}", JOB_NAME);
			return ReconciliationOutcome.SKIPPED;
		}

		UUID runId = runStore.start();
		long startedAt = System.nanoTime();
		try {
			ReconciliationSummary summary = sweep();
			runStore.complete(runId, summary);
			Duration duration = elapsedSince(startedAt);
			metrics.recordCompleted(summary, duration);
			log.info(
					"Reconciliation completed runId={} duration={} checked={} mismatches={} repaired={} orphans={} "
							+ "outboxPending={} outboxStuck={} outboxFailed={}",
					runId, duration, summary.paymentsChecked(), summary.mismatches(), summary.repaired(),
					summary.orphanProjections(), summary.outboxPending(), summary.outboxStuck(),
					summary.outboxFailed());
			return ReconciliationOutcome.COMPLETED;
		}
		catch (RuntimeException ex) {
			runStore.fail(runId, ex);
			metrics.recordFailed(elapsedSince(startedAt));
			log.error("Reconciliation failed runId={}", runId, ex);
			throw ex;
		}
		finally {
			try {
				jobLock.release(JOB_NAME, owner);
			}
			catch (RuntimeException ex) {
				// The lease expires on its own; do not mask the original failure.
				log.warn("Could not release reconciliation lease owner={}", owner, ex);
			}
		}
	}

	private ReconciliationSummary sweep() {
		long paymentsChecked = store.countPayments();

		List<ProjectionMismatch> mismatches = store.findProjectionMismatches(properties.batchSize());
		long repaired = repairer.repair(mismatches.stream().map(ProjectionMismatch::paymentId).toList());

		List<UUID> orphans = store.findOrphanProjectionIds(properties.batchSize());

		long outboxPending = store.countPendingOutboxEvents();
		long outboxStuck = store.countPendingOutboxEventsCreatedBefore(
				Instant.now().minus(properties.outboxStuckAfter()));
		long outboxFailed = store.countFailedOutboxEvents();

		List<ReconciliationFinding> findings = new ArrayList<>();
		mismatches.forEach((mismatch) -> findings.add(new ReconciliationFinding(mismatch.type(), mismatch.paymentId(),
				mismatch.expectedStatus(), mismatch.actualStatus(), mismatch.details())));
		for (UUID paymentId : orphans) {
			findings.add(new ReconciliationFinding(ReconciliationFindingType.ORPHAN_PROJECTION, paymentId, null, null,
					"Projection row has no matching payment"));
		}
		if (outboxStuck > 0) {
			findings.add(new ReconciliationFinding(ReconciliationFindingType.OUTBOX_PENDING_STUCK, null, null, null,
					outboxStuck + " events have been pending for longer than " + properties.outboxStuckAfter()));
		}
		if (outboxFailed > 0) {
			findings.add(new ReconciliationFinding(ReconciliationFindingType.OUTBOX_FAILED, null, null, null,
					outboxFailed + " events exhausted their retry budget"));
		}

		return new ReconciliationSummary(paymentsChecked, mismatches.size(), repaired, orphans.size(), outboxPending,
				outboxStuck, outboxFailed, findings);
	}

	private static Duration elapsedSince(long startedAtNanos) {
		return Duration.ofNanos(System.nanoTime() - startedAtNanos);
	}

}
