package com.paymentcore.payment.event;

/**
 * AMQP message headers that carry the event envelope. Shared by the publisher
 * and the consumers so the wire contract has one spelling.
 */
public final class PaymentEventHeaders {

	public static final String EVENT_TYPE = "eventType";

	public static final String AGGREGATE_TYPE = "aggregateType";

	public static final String AGGREGATE_ID = "aggregateId";

	public static final String OUTBOX_ATTEMPTS = "outboxAttempts";

	private PaymentEventHeaders() {
	}

}
