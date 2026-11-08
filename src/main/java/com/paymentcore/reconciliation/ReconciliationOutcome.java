package com.paymentcore.reconciliation;

public enum ReconciliationOutcome {

	/** The sweep ran and its results were persisted. */
	COMPLETED,

	/** Another instance held the lease; this run did nothing. */
	SKIPPED

}
