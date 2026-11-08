package com.paymentcore.payment.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Publishes one outbox row and moves it through the explicit outbox state
 * machine. The row is locked so concurrent dispatchers cannot publish it twice.
 */
@Service
public class OutboxPublisher {

	private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

	private final OutboxEventRepository outboxEvents;

	private final RabbitTemplate rabbitTemplate;

	private final OutboxProperties properties;

	public OutboxPublisher(OutboxEventRepository outboxEvents, RabbitTemplate rabbitTemplate,
			OutboxProperties properties) {
		this.outboxEvents = outboxEvents;
		this.rabbitTemplate = rabbitTemplate;
		this.properties = properties;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void publish(UUID outboxEventId) {
		OutboxEvent event = outboxEvents.findByIdForUpdate(outboxEventId).orElse(null);
		if (event == null || !event.isPublishableNow()) {
			return;
		}

		try {
			Message message = MessageBuilder.withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
				.setContentType(MessageProperties.CONTENT_TYPE_JSON)
				.setContentEncoding(StandardCharsets.UTF_8.name())
				.setMessageId(event.getId().toString())
				.setHeader("eventType", event.getEventType())
				.setHeader("aggregateType", event.getAggregateType())
				.setHeader("aggregateId", event.getAggregateId().toString())
				.build();
			rabbitTemplate.send(event.getExchange(), event.getRoutingKey(), message);
			event.markPublished();
			log.info("Outbox event published outboxId={} eventType={} aggregateId={}", event.getId(),
					event.getEventType(), event.getAggregateId());
		}
		catch (AmqpException ex) {
			event.recordFailure(ex.getMessage(), properties.maxAttempts(), properties.retryDelay());
			log.warn("Outbox publish failed outboxId={} attempts={} status={}", event.getId(), event.getAttempts(),
					event.getStatus(), ex);
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void retryFailed(UUID outboxEventId) {
		outboxEvents.findByIdForUpdate(outboxEventId).ifPresent(OutboxEvent::retryNow);
	}

}
