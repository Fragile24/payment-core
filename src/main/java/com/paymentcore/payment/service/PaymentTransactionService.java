package com.paymentcore.payment.service;

import com.paymentcore.payment.domain.IdempotencyRecord;
import com.paymentcore.payment.domain.Payment;
import com.paymentcore.payment.domain.PaymentAction;
import com.paymentcore.payment.domain.PaymentAuditEventType;
import com.paymentcore.payment.domain.PaymentAuditLog;
import com.paymentcore.payment.domain.PaymentStatus;
import com.paymentcore.payment.domain.PaymentTransitionOutcome;
import com.paymentcore.payment.dto.PaymentResponse;
import com.paymentcore.payment.outbox.OutboxEvent;
import com.paymentcore.payment.outbox.OutboxEventCreated;
import com.paymentcore.payment.outbox.OutboxEventRepository;
import com.paymentcore.payment.outbox.PaymentOutboxFactory;
import com.paymentcore.payment.repository.IdempotencyRecordRepository;
import com.paymentcore.payment.repository.PaymentAuditLogRepository;
import com.paymentcore.payment.repository.PaymentRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * The transactional boundary of the write side.
 *
 * <p>Every committed payment mutation carries its audit row and its integration
 * event in the same database transaction, so neither can exist without the
 * other.</p>
 */
@Service
public class PaymentTransactionService {

	private final PaymentRepository payments;

	private final PaymentAuditLogRepository auditLogs;

	private final IdempotencyRecordRepository idempotencyRecords;

	private final OutboxEventRepository outboxEvents;

	private final PaymentOutboxFactory outboxFactory;

	private final ApplicationEventPublisher events;

	private final JsonMapper jsonMapper;

	public PaymentTransactionService(PaymentRepository payments, PaymentAuditLogRepository auditLogs,
			IdempotencyRecordRepository idempotencyRecords, OutboxEventRepository outboxEvents,
			PaymentOutboxFactory outboxFactory, ApplicationEventPublisher events, JsonMapper jsonMapper) {
		this.payments = payments;
		this.auditLogs = auditLogs;
		this.idempotencyRecords = idempotencyRecords;
		this.outboxEvents = outboxEvents;
		this.outboxFactory = outboxFactory;
		this.events = events;
		this.jsonMapper = jsonMapper;
	}

	@Transactional
	public PaymentCreationResult createOrReplay(NormalizedPaymentRequest request) {
		IdempotencyRecord existing = idempotencyRecords.findByIdempotencyKey(request.idempotencyKey()).orElse(null);
		if (existing != null) {
			if (!existing.matches(request.requestHash())) {
				throw new IdempotencyKeyConflictException(request.idempotencyKey());
			}
			return new PaymentCreationResult(readSnapshot(existing), PaymentCreationOutcome.REPLAYED);
		}

		Payment payment = Payment.initiate(request.amount(), request.currency(), request.merchantReference(),
				request.description());
		payments.saveAndFlush(payment);

		PaymentResponse response = PaymentResponse.from(payment);
		idempotencyRecords.saveAndFlush(IdempotencyRecord.completed(request.idempotencyKey(), request.requestHash(),
				payment.getId(), writeSnapshot(response)));

		writeTrail(PaymentAuditLog.record(payment.getId(), PaymentAuditEventType.PAYMENT_CREATED, null,
				payment.getStatus(), auditDetails("initiated")), outboxFactory.created(payment));

		return new PaymentCreationResult(response, PaymentCreationOutcome.CREATED);
	}

	@Transactional
	public PaymentTransitionResult transition(UUID paymentId, PaymentAction action) {
		Payment payment = payments.findByIdForUpdate(paymentId)
			.orElseThrow(() -> new PaymentNotFoundException(paymentId));

		PaymentStatus previousStatus = payment.getStatus();
		PaymentTransitionOutcome outcome = payment.apply(action);
		if (outcome == PaymentTransitionOutcome.APPLIED) {
			payments.saveAndFlush(payment);
			writeTrail(PaymentAuditLog.record(payment.getId(), PaymentAuditEventType.PAYMENT_STATUS_CHANGED,
					previousStatus, action.targetStatus(), auditDetails(action.auditLabel())),
					outboxFactory.statusChanged(payment, previousStatus));
		}

		return new PaymentTransitionResult(PaymentResponse.from(payment), outcome);
	}

	/**
	 * Writes the audit row and the outbox row in the caller's transaction and
	 * hints the immediate dispatcher. The dispatch hint is delivered after commit,
	 * so a rolled-back payment never announces itself.
	 */
	private void writeTrail(PaymentAuditLog auditLog, OutboxEvent outboxEvent) {
		auditLogs.save(auditLog);
		OutboxEvent saved = outboxEvents.save(outboxEvent);
		events.publishEvent(new OutboxEventCreated(saved.getId()));
	}

	private static String auditDetails(String change) {
		return "Payment " + change + " via API";
	}

	private String writeSnapshot(PaymentResponse response) {
		try {
			return jsonMapper.writeValueAsString(response);
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("Could not serialize idempotent payment response.", ex);
		}
	}

	private PaymentResponse readSnapshot(IdempotencyRecord record) {
		try {
			return jsonMapper.readValue(record.getResponseBody(), PaymentResponse.class);
		}
		catch (JacksonException ex) {
			throw new IllegalStateException(
					"Could not deserialize idempotent payment response for key " + record.getIdempotencyKey(), ex);
		}
	}

}
