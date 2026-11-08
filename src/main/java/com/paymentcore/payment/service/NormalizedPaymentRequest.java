package com.paymentcore.payment.service;

import java.math.BigDecimal;

/**
 * A create request after normalisation, including the idempotency key it was
 * sent with. The hash is calculated from these normalised values so that
 * semantically identical retries produce the same fingerprint (for example,
 * {@code 10} and {@code 10.00}).
 */
public record NormalizedPaymentRequest(String idempotencyKey, BigDecimal amount, String currency,
		String merchantReference, String description, String requestHash) {
}
