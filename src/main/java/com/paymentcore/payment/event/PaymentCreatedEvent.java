package com.paymentcore.payment.event;

import com.paymentcore.payment.domain.Payment;
import com.paymentcore.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a payment is created.
 *
 * <p>{@code paymentVersion} is the aggregate version at the time of the event.
 * Consumers use it as an ordering key: deliveries are at-least-once and can
 * arrive out of order, so an event must never overwrite a newer projection.</p>
 */
public record PaymentCreatedEvent(UUID eventId, UUID paymentId, BigDecimal amount, String currency,
		String merchantReference, PaymentStatus status, long paymentVersion, Instant occurredAt)
		implements PaymentEvent {

	public static PaymentCreatedEvent from(Payment payment) {
		return new PaymentCreatedEvent(UUID.randomUUID(), payment.getId(), payment.getAmount(),
				payment.getCurrency(), payment.getMerchantReference(), payment.getStatus(), payment.getVersion(),
				Instant.now());
	}

}
