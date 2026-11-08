package com.paymentcore.payment.domain;

/**
 * Outcome of applying a lifecycle action to a payment.
 *
 * <p>A repeat of an action whose target status the payment already has is not an
 * error: it is a replay, and it changes nothing.</p>
 */
public enum PaymentTransitionOutcome {
	APPLIED,
	REPLAYED
}
