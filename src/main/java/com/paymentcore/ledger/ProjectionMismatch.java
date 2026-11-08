package com.paymentcore.ledger;

import java.util.UUID;

/**
 * One payment whose projection differs from the source of truth.
 *
 * @param paymentId the payment id
 * @param expectedStatus status in {@code payments}
 * @param actualStatus status in {@code payment_projection}, or {@code null} when
 * the projection row is missing
 */
public record ProjectionMismatch(UUID paymentId, String expectedStatus, String actualStatus) {

	public boolean isMissing() {
		return actualStatus == null;
	}

}
