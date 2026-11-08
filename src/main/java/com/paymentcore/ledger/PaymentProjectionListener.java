package com.paymentcore.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Consumes payment events from the projection queue and applies them to the
 * read model.
 *
 * <p>Failures are not swallowed: the container factory retries the listener and
 * then rejects the message, which the broker dead-letters to
 * {@code payment.projection.dlq}. A poison message is therefore visible and
 * replayable instead of blocking the queue forever.</p>
 *
 * <p>The consumer owns its queue. Publisher code only knows the exchange, so a
 * second consumer group can be added without touching the payment service.</p>
 */
@Component
@ConditionalOnProperty(name = "app.ledger.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class PaymentProjectionListener {

	private static final Logger log = LoggerFactory.getLogger(PaymentProjectionListener.class);

	private static final String UNKNOWN_EVENT = "UNKNOWN";

	private final PaymentProjectionService projectionService;

	private final ProjectionConsumerMetrics metrics;

	public PaymentProjectionListener(PaymentProjectionService projectionService, ProjectionConsumerMetrics metrics) {
		this.projectionService = projectionService;
		this.metrics = metrics;
	}

	@RabbitListener(queues = "${app.messaging.projection-queue}",
			containerFactory = "ledgerListenerContainerFactory")
	public void onPaymentEvent(Message message) {
		String eventType = eventType(message);
		try {
			UUID deliveryId = deliveryId(message);
			String payload = new String(message.getBody(), StandardCharsets.UTF_8);
			ProjectionOutcome outcome = projectionService.handle(deliveryId, eventType, payload);
			metrics.record(outcome, eventType);
			if (outcome == ProjectionOutcome.DUPLICATE) {
				log.info("Duplicate payment event ignored deliveryId={} eventType={}", deliveryId, eventType);
			}
		}
		catch (RuntimeException ex) {
			metrics.recordFailed(eventType);
			log.warn("Payment event processing failed; retrying then dead-lettering eventType={}", eventType, ex);
			throw ex;
		}
	}

	private static String eventType(Message message) {
		Object header = message.getMessageProperties().getHeaders().get("eventType");
		return header instanceof String value && !value.isBlank() ? value.toUpperCase(Locale.ROOT) : UNKNOWN_EVENT;
	}

	private static UUID deliveryId(Message message) {
		String messageId = message.getMessageProperties().getMessageId();
		if (messageId == null || messageId.isBlank()) {
			throw new IllegalArgumentException("Payment event is missing a message id");
		}
		return UUID.fromString(messageId);
	}

}
