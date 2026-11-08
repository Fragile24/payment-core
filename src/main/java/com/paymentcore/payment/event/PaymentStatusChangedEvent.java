package com.paymentcore.payment.event;

import com.paymentcore.payment.domain.Payment;
import com.paymentcore.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentStatusChangedEvent(UUID eventId, UUID paymentId, PaymentStatus fromStatus,
		PaymentStatus toStatus, BigDecimal amount, String currency, Instant occurredAt) implements PaymentEvent {

	public static PaymentStatusChangedEvent from(Payment payment, PaymentStatus fromStatus) {
		return new PaymentStatusChangedEvent(UUID.randomUUID(), payment.getId(), fromStatus, payment.getStatus(),
				payment.getAmount(), payment.getCurrency(), Instant.now());
	}

}
