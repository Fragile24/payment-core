package com.paymentcore.payment.event;

import java.util.Arrays;
import java.util.Optional;

/**
 * The event types this service publishes.
 *
 * <p>The wire name is the value stored in {@code outbox_events.event_type} and
 * sent in the {@code eventType} header, so producers and consumers agree on it
 * through this enum instead of through two copies of a string.</p>
 */
public enum PaymentEventType {

	CREATED("PAYMENT_CREATED"),

	STATUS_CHANGED("PAYMENT_STATUS_CHANGED");

	private final String wireName;

	PaymentEventType(String wireName) {
		this.wireName = wireName;
	}

	public String wireName() {
		return wireName;
	}

	public static Optional<PaymentEventType> fromWireName(String wireName) {
		return Arrays.stream(values()).filter((type) -> type.wireName.equals(wireName)).findFirst();
	}

}
