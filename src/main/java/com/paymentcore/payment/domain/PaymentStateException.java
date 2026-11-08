package com.paymentcore.payment.domain;

import java.util.UUID;

public class PaymentStateException extends RuntimeException {

	private final PaymentStatus currentStatus;

	private final PaymentAction action;

	private final PaymentStatus targetStatus;

	public PaymentStateException(UUID paymentId, PaymentStatus currentStatus, PaymentAction action) {
		super("Payment '" + paymentId + "' cannot perform " + action + " from " + currentStatus + ".");
		this.currentStatus = currentStatus;
		this.action = action;
		this.targetStatus = action.targetStatus();
	}

	public PaymentStatus getCurrentStatus() {
		return currentStatus;
	}

	public PaymentAction getAction() {
		return action;
	}

	public PaymentStatus getTargetStatus() {
		return targetStatus;
	}

}
