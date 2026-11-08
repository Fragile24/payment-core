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

import java.util.List;
import java.util.UUID;

@Service
public class PaymentService {

	/**
	 * First attempt plus one retry for the concurrent idempotency-key insert race.
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
	 * <p>The retry handles a concurrent first request: the unique database
	 * constraint allows only one insert, and the losing transaction retries by
	 * replaying the winning record.</p>
	 */
	public PaymentCreationResult create(String idempotencyKey, CreatePaymentRequest request) {
		NormalizedPaymentRequest normalized = PaymentRequestNormalizer.normalize(idempotencyKey, request);
		for (int attempt = 1;; attempt++) {
			try {
				PaymentCreationResult result = transactionService.createOrReplay(normalized);
				metrics.recordCreated(result.outcome());
				return result;
			}
			catch (DataIntegrityViolationException ex) {
				if (attempt == MAX_CREATE_ATTEMPTS) {
					throw ex;
				}
			}
		}
	}

	public PaymentTransitionResult transition(UUID paymentId, PaymentAction action) {
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

}
