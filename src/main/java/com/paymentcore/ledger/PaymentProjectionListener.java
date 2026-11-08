package com.paymentcore.ledger;

import com.paymentcore.payment.event.PaymentEventHeaders;
import com.paymentcore.payment.event.PaymentEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
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

	/**
	 * Metric tag for a message whose type header is missing or unknown. The tag
	 * stays bounded instead of echoing arbitrary header values into a metric.
	 */
	private static final String UNKNOWN_EVENT_TYPE = "UNKNOWN";

	private final PaymentProjectionService projectionService;

	private final ProjectionConsumerMetrics metrics;

	public PaymentProjectionListener(PaymentProjectionService projectionService, ProjectionConsumerMetrics metrics) {
		this.projectionService = projectionService;
		this.metrics = metrics;
	}

	@RabbitListener(queues = "${app.messaging.projection-queue}",
			containerFactory = "ledgerListenerContainerFactory")
	public void onPaymentEvent(Message message) {
		String wireName = eventTypeHeader(message);
		Optional<PaymentEventType> eventType = PaymentEventType.fromWireName(wireName);
		try {
			UUID deliveryId = deliveryId(message);
			String payload = new String(message.getBody(), StandardCharsets.UTF_8);
			PaymentEventType type = eventType.orElseThrow(
					() -> new IllegalArgumentException("Unsupported payment event type: " + wireName));
			ProjectionOutcome outcome = projectionService.handle(deliveryId, type, payload);
			metrics.record(outcome, type.wireName());
			if (outcome == ProjectionOutcome.DUPLICATE) {
				log.info("Duplicate payment event ignored deliveryId={} eventType={}", deliveryId, type.wireName());
			}
		}
		catch (RuntimeException ex) {
			metrics.recordFailed(eventType.map(PaymentEventType::wireName).orElse(UNKNOWN_EVENT_TYPE));
			log.warn("Payment event processing failed; retrying then dead-lettering eventType={}", wireName, ex);
			throw ex;
		}
	}

	private static String eventTypeHeader(Message message) {
		return message.getMessageProperties().getHeaders().get(PaymentEventHeaders.EVENT_TYPE) instanceof String value
				? value : null;
	}

	/**
	 * The publisher sets the AMQP message id to the outbox row id, so a redelivery
	 * of the same row carries the same id and is deduplicated on it.
	 */
	private static UUID deliveryId(Message message) {
		String messageId = message.getMessageProperties().getMessageId();
		if (messageId == null || messageId.isBlank()) {
			throw new IllegalArgumentException("Payment event is missing a message id");
		}
		return UUID.fromString(messageId);
	}

}
