package com.paymentcore.payment.service;

import com.paymentcore.payment.domain.PaymentAction;
import com.paymentcore.payment.dto.CreatePaymentRequest;
import com.paymentcore.payment.dto.PaymentAuditResponse;
import com.paymentcore.payment.dto.PaymentResponse;
import com.paymentcore.payment.repository.PaymentAuditLogRepository;
import com.paymentcore.payment.repository.PaymentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Currency;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class PaymentService {

	private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{8,128}");

	private static final String FINGERPRINT_VERSION = "v1";

	/**
	 * First attempt plus one retry for the concurrent idempotency insert race.
	 */
	private static final int MAX_CREATE_ATTEMPTS = 2;

	private final PaymentTransactionService transactionService;
	private final PaymentRepository payments;
	private final PaymentAuditLogRepository auditLogs;
	private final PaymentMetrics metrics;

	public PaymentService(PaymentTransactionService transactionService, PaymentRepository payments,
			PaymentAuditLogRepository auditLogs, PaymentMetrics metrics) {
		this.transactionService = transactionService;
		this.payments = payments;
		this.auditLogs = auditLogs;
		this.metrics = metrics;
	}

	/**
	 * Creates a payment or replays the previously stored result for the same
	 * idempotency key.
	 *
	 * <p>The retry loop handles a concurrent first request: the unique database
	 * constraint allows only one insert, and the losing transaction retries by
	 * looking the winning payment up.</p>
	 */
	public PaymentCreationResult create(String idempotencyKey, CreatePaymentRequest request) {
		String key = normalizeIdempotencyKey(idempotencyKey);
		NormalizedPaymentRequest normalized = normalize(request);

		for (int attempt = 1; attempt <= MAX_CREATE_ATTEMPTS; attempt++) {
			try {
				PaymentCreationResult result = transactionService.createOrReplay(key, normalized);
				metrics.recordCreated(result.outcome());
				return result;
			}
			catch (DataIntegrityViolationException ex) {
				if (attempt == MAX_CREATE_ATTEMPTS) {
					throw ex;
				}
			}
		}
		throw new IllegalStateException("Payment creation retry loop finished unexpectedly.");
	}

	public PaymentTransitionResult authorize(UUID paymentId) {
		return transition(paymentId, PaymentAction.AUTHORIZE);
	}

	public PaymentTransitionResult capture(UUID paymentId) {
		return transition(paymentId, PaymentAction.CAPTURE);
	}

	public PaymentTransitionResult refund(UUID paymentId) {
		return transition(paymentId, PaymentAction.REFUND);
	}

	public PaymentTransitionResult cancel(UUID paymentId) {
		return transition(paymentId, PaymentAction.CANCEL);
	}

	public PaymentTransitionResult fail(UUID paymentId) {
		return transition(paymentId, PaymentAction.FAIL);
	}

	private PaymentTransitionResult transition(UUID paymentId, PaymentAction action) {
		PaymentTransitionResult result = transactionService.transition(paymentId, action);
		metrics.recordTransition(action, result.outcome());
		return result;
	}

	@Transactional(readOnly = true)
	public PaymentResponse get(UUID paymentId) {
		return payments.findById(paymentId)
			.map(PaymentResponse::from)
			.orElseThrow(() -> new PaymentNotFoundException(paymentId));
	}

	@Transactional(readOnly = true)
	public Page<PaymentResponse> list(Pageable pageable) {
		return payments.findAll(pageable).map(PaymentResponse::from);
	}

	@Transactional(readOnly = true)
	public List<PaymentAuditResponse> getAuditTrail(UUID paymentId) {
		if (!payments.existsById(paymentId)) {
			throw new PaymentNotFoundException(paymentId);
		}
		return auditLogs.findByPaymentIdOrderByCreatedAtAsc(paymentId)
			.stream()
			.map(PaymentAuditResponse::from)
			.toList();
	}

	private String normalizeIdempotencyKey(String rawKey) {
		if (rawKey == null || rawKey.isBlank()) {
			throw new InvalidPaymentRequestException("Idempotency-Key", "Idempotency-Key header is required.");
		}
		String key = rawKey.trim();
		if (!IDEMPOTENCY_KEY_PATTERN.matcher(key).matches()) {
			throw new InvalidPaymentRequestException("Idempotency-Key",
					"Idempotency-Key must be 8-128 characters and use only letters, digits, '.', '_', ':' or '-'.");
		}
		return key;
	}

	private NormalizedPaymentRequest normalize(CreatePaymentRequest request) {
		if (request == null || request.amount() == null || request.currency() == null) {
			throw new InvalidPaymentRequestException("request", "amount and currency are required.");
		}

		BigDecimal amount;
		try {
			amount = request.amount().setScale(2, RoundingMode.UNNECESSARY);
		}
		catch (ArithmeticException ex) {
			throw new InvalidPaymentRequestException("amount", "amount must have at most 2 decimal places.");
		}
		if (amount.signum() <= 0) {
			throw new InvalidPaymentRequestException("amount", "amount must be greater than zero.");
		}
		if (amount.precision() - amount.scale() > 17) {
			throw new InvalidPaymentRequestException("amount", "amount is too large.");
		}

		String currency = request.currency().trim().toUpperCase(Locale.ROOT);
		try {
			Currency.getInstance(currency);
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidPaymentRequestException("currency", "currency must be a valid ISO 4217 code.");
		}

		String merchantReference = trimToNull(request.merchantReference());
		if (merchantReference != null && merchantReference.length() > 128) {
			throw new InvalidPaymentRequestException("merchantReference",
					"merchantReference must be at most 128 characters.");
		}

		String description = trimToNull(request.description());
		if (description != null && description.length() > 255) {
			throw new InvalidPaymentRequestException("description", "description must be at most 255 characters.");
		}

		String requestHash = fingerprint(amount, currency, merchantReference, description);
		return new NormalizedPaymentRequest(amount, currency, merchantReference, description, requestHash);
	}

	private static String fingerprint(BigDecimal amount, String currency, String merchantReference,
			String description) {
		// Length-prefix variable fields so that embedded separators/newlines cannot
		// make two different payloads produce the same fingerprint.
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
