package com.paymentcore.payment.event;

import com.paymentcore.payment.domain.Payment;
import com.paymentcore.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentCreatedEvent(UUID eventId, UUID paymentId, BigDecimal amount, String currency,
		String merchantReference, PaymentStatus status, Instant occurredAt) implements PaymentEvent {

	public static PaymentCreatedEvent from(Payment payment) {
		return new PaymentCreatedEvent(UUID.randomUUID(), payment.getId(), payment.getAmount(),
				payment.getCurrency(), payment.getMerchantReference(), payment.getStatus(), Instant.now());
	}

}
