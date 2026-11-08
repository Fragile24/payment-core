package com.paymentcore.payment.dto;

import com.paymentcore.payment.domain.Payment;
import com.paymentcore.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The payment representation returned by the API.
 *
 * <p>The same record is serialized into {@code idempotency_keys.response_body} at
 * creation time and read back on a replay, so its component list is a stored
 * format as well as an API contract: renaming or removing a component changes
 * what old rows mean.</p>
 */
public record PaymentResponse(UUID id, PaymentStatus status, BigDecimal amount, String currency,
		String merchantReference, String description, long version, Instant createdAt, Instant updatedAt,
		Instant authorizedAt, Instant capturedAt, Instant refundedAt, Instant cancelledAt, Instant failedAt) {

	public static PaymentResponse from(Payment payment) {
		return new PaymentResponse(payment.getId(), payment.getStatus(), payment.getAmount(), payment.getCurrency(),
				payment.getMerchantReference(), payment.getDescription(), payment.getVersion(), payment.getCreatedAt(),
				payment.getUpdatedAt(), payment.getAuthorizedAt(), payment.getCapturedAt(), payment.getRefundedAt(),
				payment.getCancelledAt(), payment.getFailedAt());
	}

}
