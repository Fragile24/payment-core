package com.paymentcore.payment.domain;

import java.time.Instant;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * One lifecycle action: the status it moves the payment to, the statuses it may
 * be applied from, the lifecycle timestamp it stamps, and how an audit entry
 * describes it.
 *
 * <p>Everything an action means is declared on the constant, so there is no
 * separate transition table to keep in sync and adding an action cannot leave
 * one of these decisions unmade.</p>
 */
public enum PaymentAction {

	AUTHORIZE(PaymentStatus.AUTHORIZED, Set.of(PaymentStatus.INITIATED), Payment::markAuthorized, "authorized"),

	CAPTURE(PaymentStatus.CAPTURED, Set.of(PaymentStatus.AUTHORIZED), Payment::markCaptured, "captured"),

	REFUND(PaymentStatus.REFUNDED, Set.of(PaymentStatus.CAPTURED), Payment::markRefunded, "refunded"),

	CANCEL(PaymentStatus.CANCELLED, Set.of(PaymentStatus.INITIATED, PaymentStatus.AUTHORIZED),
			Payment::markCancelled, "cancelled"),

	FAIL(PaymentStatus.FAILED, Set.of(PaymentStatus.INITIATED, PaymentStatus.AUTHORIZED), Payment::markFailed,
			"failed");

	private final PaymentStatus targetStatus;

	private final Set<PaymentStatus> sourceStatuses;

	private final BiConsumer<Payment, Instant> timestamp;

	private final String auditLabel;

	PaymentAction(PaymentStatus targetStatus, Set<PaymentStatus> sourceStatuses,
			BiConsumer<Payment, Instant> timestamp, String auditLabel) {
		this.targetStatus = targetStatus;
		this.sourceStatuses = sourceStatuses;
		this.timestamp = timestamp;
		this.auditLabel = auditLabel;
	}

	public PaymentStatus targetStatus() {
		return targetStatus;
	}

	public boolean appliesFrom(PaymentStatus currentStatus) {
		return sourceStatuses.contains(currentStatus);
	}

	/**
	 * A replay is an action whose target is the status the payment already has.
	 */
	public boolean isReplayOf(PaymentStatus currentStatus) {
		return currentStatus == targetStatus;
	}

	/**
	 * How the audit trail describes the change, for example {@code "captured"}.
	 */
	public String auditLabel() {
		return auditLabel;
	}

	void stampOn(Payment payment, Instant at) {
		timestamp.accept(payment, at);
	}

}
