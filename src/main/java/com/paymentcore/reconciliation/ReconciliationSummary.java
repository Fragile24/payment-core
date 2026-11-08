package com.paymentcore.reconciliation;

import java.util.List;

/**
 * Result of one sweep, persisted as a {@code reconciliation_runs} row plus its
 * findings.
 */
public record ReconciliationSummary(long paymentsChecked, long mismatches, long repaired, long orphanProjections,
		long outboxPending, long outboxStuck, long outboxFailed, List<ReconciliationFinding> findings) {
}
