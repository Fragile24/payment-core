package com.paymentcore.payment.domain;

public enum PaymentAction {

	AUTHORIZE(PaymentStatus.AUTHORIZED),
	CAPTURE(PaymentStatus.CAPTURED),
	REFUND(PaymentStatus.REFUNDED),
	CANCEL(PaymentStatus.CANCELLED),
	FAIL(PaymentStatus.FAILED);

	private final PaymentStatus targetStatus;

	PaymentAction(PaymentStatus targetStatus) {
		this.targetStatus = targetStatus;
	}

	public PaymentStatus targetStatus() {
		return targetStatus;
	}

}
