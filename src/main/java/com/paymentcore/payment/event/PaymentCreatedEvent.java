package com.paymentcore.payment.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a payment is created.
 *
 * <p>{@code paymentVersion} is the aggregate version at the time of the event.
 * Consumers use it as an ordering key: deliveries are at-least-once and can
 * arrive out of order, so an event must never overwrite a newer projection.</p>
 *
 * <p>{@code status} is the wire name of the status, so a consumer of the event
 * does not depend on the domain enum.</p>
 */
public record PaymentCreatedEvent(UUID eventId, UUID paymentId, BigDecimal amount, String currency,
		String merchantReference, String status, long paymentVersion, Instant occurredAt) implements PaymentEvent {
}
