package com.paymentcore.payment.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * Publishes one outbox row and moves it through the explicit outbox state
 * machine. The row is locked so concurrent dispatchers cannot publish it twice.
 *
 * <p>The publish is only considered successful once RabbitMQ confirms the
 * message ({@link ConfirmedMessagePublisher}), so {@code PUBLISHED} now means
 * "broker acknowledged", not "written to the socket". The row lock is held for
 * the confirm round trip; the wait is bounded by
 * {@code app.outbox.confirm-timeout}.</p>
 */
@Service
public class OutboxPublisher {

	private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

	private final OutboxEventRepository outboxEvents;

	private final ConfirmedMessagePublisher messagePublisher;

	private final OutboxProperties properties;

	private final OutboxPublishMetrics metrics;

	public OutboxPublisher(OutboxEventRepository outboxEvents, ConfirmedMessagePublisher messagePublisher,
			OutboxProperties properties, OutboxPublishMetrics metrics) {
		this.outboxEvents = outboxEvents;
		this.messagePublisher = messagePublisher;
		this.properties = properties;
		this.metrics = metrics;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void publish(UUID outboxEventId) {
		OutboxEvent event = outboxEvents.findByIdForUpdate(outboxEventId).orElse(null);
		if (event == null || !event.isPublishableNow()) {
			return;
		}

		long startedAt = System.nanoTime();
		try {
			messagePublisher.publish(event);
			event.markPublished();
			metrics.recordConfirmed(elapsedSince(startedAt));
			log.info("Outbox event published outboxId={} eventType={} aggregateId={}", event.getId(),
					event.getEventType(), event.getAggregateId());
		}
		catch (RuntimeException ex) {
			event.recordFailure(describe(ex), properties.maxAttempts(), properties.retryDelay());
			metrics.recordFailed(failureReason(ex), elapsedSince(startedAt));
			log.warn("Outbox publish failed outboxId={} attempts={} status={}", event.getId(), event.getAttempts(),
					event.getStatus(), ex);
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void retryFailed(UUID outboxEventId) {
		outboxEvents.findByIdForUpdate(outboxEventId).ifPresent(OutboxEvent::retryNow);
	}

	private String describe(RuntimeException ex) {
		String message = ex.getMessage();
		return message != null ? message : ex.getClass().getSimpleName();
	}

	/**
	 * Failure reason tag. Anything that is not a publish-confirmation failure is
	 * classified as a connection problem, which is what a broker outage looks
	 * like from here.
	 */
	private static String failureReason(RuntimeException ex) {
		return ex instanceof OutboxPublishException publishException
				? publishException.getReason().name().toLowerCase(Locale.ROOT) : "connection";
	}

	private static Duration elapsedSince(long startedAtNanos) {
		return Duration.ofNanos(System.nanoTime() - startedAtNanos);
	}

}
