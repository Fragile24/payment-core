package com.paymentcore.payment.service;

import com.paymentcore.payment.domain.IdempotencyRecord;
import com.paymentcore.payment.domain.Payment;
import com.paymentcore.payment.dto.CreatePaymentRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Currency;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a create request into the canonical form the payment is stored and
 * fingerprinted from.
 *
 * <p>Two callers reach payment creation: the HTTP layer, which has already
 * rejected shape errors through bean validation, and application code that calls
 * {@link PaymentService} directly. The rules here are therefore the ones that
 * must hold regardless of how the request arrived: an amount that can be stored
 * and hashed unambiguously, an ISO currency, values within the documented
 * lengths, and a usable idempotency key.</p>
 */
public final class PaymentRequestNormalizer {

	public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	private static final int IDEMPOTENCY_KEY_MIN_LENGTH = 8;

	private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{" + IDEMPOTENCY_KEY_MIN_LENGTH + ","
			+ IdempotencyRecord.MAX_IDEMPOTENCY_KEY_LENGTH + "}");

	private static final String FINGERPRINT_VERSION = "v1";

	private PaymentRequestNormalizer() {
	}

	public static NormalizedPaymentRequest normalize(String idempotencyKey, CreatePaymentRequest request) {
		String key = requireIdempotencyKey(idempotencyKey);
		if (request == null || request.amount() == null || request.currency() == null) {
			throw new InvalidPaymentRequestException("request", "amount and currency are required.");
		}

		BigDecimal amount = requireAmount(request.amount());
		String currency = requireCurrency(request.currency());
		String merchantReference = requireLength("merchantReference", trimToNull(request.merchantReference()),
				Payment.MERCHANT_REFERENCE_MAX_LENGTH);
		String description = requireLength("description", trimToNull(request.description()),
				Payment.DESCRIPTION_MAX_LENGTH);

		return new NormalizedPaymentRequest(key, amount, currency, merchantReference, description,
				fingerprint(amount, currency, merchantReference, description));
	}

	private static String requireIdempotencyKey(String rawKey) {
		if (rawKey == null || rawKey.isBlank()) {
			throw new InvalidPaymentRequestException(IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY_HEADER + " is required.");
		}
		String key = rawKey.trim();
		if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
			throw new InvalidPaymentRequestException(IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY_HEADER + " must be "
					+ IDEMPOTENCY_KEY_MIN_LENGTH + "-" + IdempotencyRecord.MAX_IDEMPOTENCY_KEY_LENGTH
					+ " characters and use only letters, digits, '.', '_', ':' or '-'.");
		}
		return key;
	}

	private static BigDecimal requireAmount(BigDecimal rawAmount) {
		BigDecimal amount;
		try {
			amount = rawAmount.setScale(Payment.MONEY_SCALE, RoundingMode.UNNECESSARY);
		}
		catch (ArithmeticException ex) {
			throw new InvalidPaymentRequestException("amount",
					"amount must have at most " + Payment.MONEY_SCALE + " decimal places.");
		}
		if (amount.signum() <= 0) {
			throw new InvalidPaymentRequestException("amount", "amount must be greater than zero.");
		}
		if (amount.precision() - amount.scale() > Payment.AMOUNT_MAX_INTEGER_DIGITS) {
			throw new InvalidPaymentRequestException("amount", "amount is too large.");
		}
		return amount;
	}

	private static String requireCurrency(String rawCurrency) {
		String currency = rawCurrency.trim().toUpperCase(Locale.ROOT);
		try {
			Currency.getInstance(currency);
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidPaymentRequestException("currency", "currency must be a valid ISO 4217 code.");
		}
		return currency;
	}

	private static String requireLength(String field, String value, int maxLength) {
		if (value != null && value.length() > maxLength) {
			throw new InvalidPaymentRequestException(field, field + " must be at most " + maxLength + " characters.");
		}
		return value;
	}

	private static String fingerprint(BigDecimal amount, String currency, String merchantReference,
			String description) {
		String canonical = FINGERPRINT_VERSION + "|" + amount.toPlainString() + "|" + currency + "|"
				+ canonicalPart(merchantReference) + "|" + canonicalPart(description);
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available in this JVM.", ex);
		}
	}

	/**
	 * Encodes an optional field with its length so that no combination of
	 * separators inside two different values can produce the same fingerprint.
	 */
	private static String canonicalPart(String value) {
		return value == null ? "-1:" : value.length() + ":" + value;
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

}
