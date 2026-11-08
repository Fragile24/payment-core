package com.paymentcore.payment.outbox;

public class OutboxStateException extends RuntimeException {

	public OutboxStateException(OutboxStatus currentStatus, OutboxStatus targetStatus) {
		super("Outbox event cannot transition from " + currentStatus + " to " + targetStatus + ".");
	}

}
