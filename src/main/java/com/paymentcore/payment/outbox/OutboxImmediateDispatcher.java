package com.paymentcore.payment.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Best-effort immediate dispatch after the payment transaction commits.
 * If this fails, the pending outbox row is still durable and the scheduler
 * retries it.
 */
@Component
public class OutboxImmediateDispatcher {

	private static final Logger log = LoggerFactory.getLogger(OutboxImmediateDispatcher.class);

	private final OutboxPublisher outboxPublisher;

	public OutboxImmediateDispatcher(OutboxPublisher outboxPublisher) {
		this.outboxPublisher = outboxPublisher;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onOutboxEventCreated(OutboxEventCreated event) {
		try {
			outboxPublisher.publish(event.outboxEventId());
		}
		catch (RuntimeException ex) {
			log.warn("Immediate outbox dispatch failed outboxId={}; scheduler will retry", event.outboxEventId(), ex);
		}
	}

}
