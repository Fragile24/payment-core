package com.paymentcore.payment.domain;

/**
 * Lifecycle of a payment in the sandbox.
 *
 * <pre>
 * INITIATED --authorize--> AUTHORIZED --capture--> CAPTURED --refund--> REFUNDED
 *     |                         |
 *     +---------cancel----------+--> CANCELLED
 *     |                         |
 *     +----------fail-----------+--> FAILED
 * </pre>
 */
public enum PaymentStatus {
	INITIATED,
	AUTHORIZED,
	CAPTURED,
	FAILED,
	CANCELLED,
	REFUNDED
}
