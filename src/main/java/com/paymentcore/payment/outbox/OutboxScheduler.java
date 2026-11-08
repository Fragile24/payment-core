package com.paymentcore.payment.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Retries pending outbox rows that were not delivered immediately. This is the
 * recovery side of the outbox state machine.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {

	private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

	private final OutboxEventRepository outboxEvents;

	private final OutboxPublisher outboxPublisher;

	public OutboxScheduler(OutboxEventRepository outboxEvents, OutboxPublisher outboxPublisher) {
		this.outboxEvents = outboxEvents;
		this.outboxPublisher = outboxPublisher;
	}

	@Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:5000}")
	public void dispatchPending() {
		List<UUID> due = outboxEvents.findDueIds(OutboxStatus.PENDING, Instant.now(), PageRequest.of(0, 50));
		for (UUID outboxEventId : due) {
			try {
				outboxPublisher.publish(outboxEventId);
			}
			catch (RuntimeException ex) {
				log.warn("Outbox retry failed outboxId={}", outboxEventId, ex);
			}
		}
	}

}
