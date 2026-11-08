package com.paymentcore.payment.service;

import com.paymentcore.payment.dto.PaymentResponse;
import com.paymentcore.payment.domain.IdempotencyRecord;
import com.paymentcore.payment.domain.Payment;
import com.paymentcore.payment.domain.PaymentAction;
import com.paymentcore.payment.domain.PaymentAuditEventType;
import com.paymentcore.payment.domain.PaymentAuditLog;
import com.paymentcore.payment.domain.PaymentStateMachine;
import com.paymentcore.payment.domain.PaymentStatus;
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
	public PaymentCreationResult createOrReplay(String idempotencyKey, NormalizedPaymentRequest request) {
		IdempotencyRecord existing = idempotencyRecords.findByIdempotencyKey(idempotencyKey).orElse(null);
		if (existing != null) {
			if (!existing.matches(request.requestHash())) {
				throw new IdempotencyKeyConflictException(idempotencyKey);
			}
			return new PaymentCreationResult(readSnapshot(existing), PaymentCreationOutcome.REPLAYED);
		}

		Payment payment = Payment.initiate(request.amount(), request.currency(), request.merchantReference(),
				request.description());
		payments.saveAndFlush(payment);

		auditLogs.save(PaymentAuditLog.record(payment.getId(), PaymentAuditEventType.PAYMENT_CREATED, null,
				payment.getStatus(), "Payment initiated via API"));

		PaymentResponse response = PaymentResponse.from(payment);
		idempotencyRecords.saveAndFlush(IdempotencyRecord.completed(idempotencyKey, request.requestHash(),
				payment.getId(), 201, writeSnapshot(response)));

		OutboxEvent outboxEvent = outboxEvents.save(outboxFactory.created(payment));
		events.publishEvent(new OutboxEventCreated(outboxEvent.getId()));

		return new PaymentCreationResult(response, PaymentCreationOutcome.CREATED);
	}

	@Transactional
	public PaymentTransitionResult transition(UUID paymentId, PaymentAction action) {
		Payment payment = payments.findByIdForUpdate(paymentId)
			.orElseThrow(() -> new PaymentNotFoundException(paymentId));

		if (PaymentStateMachine.isReplay(action, payment.getStatus())) {
			return new PaymentTransitionResult(PaymentResponse.from(payment), PaymentTransitionOutcome.REPLAYED);
		}

		PaymentStatus previousStatus = payment.getStatus();
		payment.apply(action);
		payments.saveAndFlush(payment);

		auditLogs.save(PaymentAuditLog.record(payment.getId(), PaymentAuditEventType.PAYMENT_STATUS_CHANGED,
				previousStatus, action.targetStatus(), transitionDetails(action)));

		OutboxEvent outboxEvent = outboxEvents.save(outboxFactory.statusChanged(payment, previousStatus));
		events.publishEvent(new OutboxEventCreated(outboxEvent.getId()));

		return new PaymentTransitionResult(PaymentResponse.from(payment), PaymentTransitionOutcome.APPLIED);
	}

	private static String transitionDetails(PaymentAction action) {
		return switch (action) {
			case AUTHORIZE -> "Payment authorized via API";
			case CAPTURE -> "Payment captured via API";
			case REFUND -> "Payment refunded via API";
			case CANCEL -> "Payment cancelled via API";
			case FAIL -> "Payment failed via API";
		};
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
