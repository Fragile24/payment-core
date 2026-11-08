package com.paymentcore.reconciliation;

/**
 * The vocabulary of divergence a sweep can find and report.
 *
 * <p>The names are the {@code reconciliation_findings.type} values and the
 * {@code reconciliation.findings} metric tag.</p>
 */
public enum ReconciliationFindingType {

	/** The projection row does not exist. Repaired from the source. */
	MISSING_PROJECTION,

	/** The projection row disagrees with the source on status or version. Repaired. */
	STALE_PROJECTION,

	/** The projection has a payment that does not exist. Reported only. */
	ORPHAN_PROJECTION,

	/** Outbox events have been pending for longer than the configured window. */
	OUTBOX_PENDING_STUCK,

	/** Outbox events have exhausted their retry budget. */
	OUTBOX_FAILED

}
