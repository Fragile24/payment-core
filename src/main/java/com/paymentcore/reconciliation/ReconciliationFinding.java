package com.paymentcore.reconciliation;

import java.util.UUID;

/**
 * One concrete divergence found by a reconciliation run.
 *
 * @param type finding category
 * @param paymentId payment concerned, {@code null} for aggregate findings
 * @param expectedStatus status in the source of truth
 * @param actualStatus status in the projection
 * @param details human-readable context for the operator
 */
public record ReconciliationFinding(ReconciliationFindingType type, UUID paymentId, String expectedStatus,
		String actualStatus, String details) {
}
