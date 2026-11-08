package com.paymentcore.payment.outbox;

import com.paymentcore.payment.event.PaymentEventHeaders;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes one outbox message and waits until RabbitMQ confirms it.
 *
 * <p>Spring AMQP's {@code basic.publish} is asynchronous: a successful return
 * only means the frame was written to the socket. With
 * {@code spring.rabbitmq.publisher-confirm-type=correlated} the broker sends an
 * {@code ack}/{@code nack} per message, correlated through
 * {@link CorrelationData}. Waiting for that confirm is what justifies moving
 * the outbox row to {@code PUBLISHED}.</p>
 *
 * <p>A confirmed message can still be unroutable. With
 * {@code publisher-returns} and the mandatory flag, the broker returns such a
 * message before confirming it; the returned message is stored on the
 * correlation data and is treated as a failed publish here instead of a silent
 * success.</p>
 *
 * <p>Every non-successful outcome is reported as
 * {@link OutboxPublishException}, which the caller records as a retryable
 * attempt.</p>
 */
@Component
public class ConfirmedMessagePublisher {

	private final RabbitTemplate rabbitTemplate;

	private final OutboxProperties properties;

	public ConfirmedMessagePublisher(RabbitTemplate rabbitTemplate, OutboxProperties properties) {
		this.rabbitTemplate = rabbitTemplate;
		this.properties = properties;
	}

	/**
	 * Sends the event and blocks until the broker confirms it.
	 * @param event the outbox row to publish
	 * @throws OutboxPublishException when the send fails, the broker nacks or
	 * returns the message, or the confirm times out
	 */
	public void publish(OutboxEvent event) {
		// The id correlates the confirm with this send. It must be unique across
		// retries of the same event, otherwise a late confirm from a previous
		// attempt could be mistaken for the current one.
		CorrelationData correlationData = new CorrelationData("%s:%s".formatted(event.getId(), UUID.randomUUID()));
		try {
			rabbitTemplate.send(event.getExchange(), event.getRoutingKey(), toMessage(event), correlationData);
		}
		catch (AmqpException ex) {
			throw new OutboxPublishException(OutboxPublishException.Reason.CONNECTION,
					"could not send outboxId=%s: %s".formatted(event.getId(), ex.getMessage()), ex);
		}
		awaitConfirmation(event, correlationData);
	}

	private Message toMessage(OutboxEvent event) {
		return MessageBuilder.withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
			.setContentType(MessageProperties.CONTENT_TYPE_JSON)
			.setContentEncoding(StandardCharsets.UTF_8.name())
			.setMessageId(event.getId().toString())
			.setHeader(PaymentEventHeaders.EVENT_TYPE, event.getEventType())
			.setHeader(PaymentEventHeaders.AGGREGATE_TYPE, event.getAggregateType())
			.setHeader(PaymentEventHeaders.AGGREGATE_ID, event.getAggregateId().toString())
			.setHeader(PaymentEventHeaders.OUTBOX_ATTEMPTS, event.getAttempts())
			.build();
	}

	private void awaitConfirmation(OutboxEvent event, CorrelationData correlationData) {
		Duration confirmTimeout = properties.confirmTimeout();
		CorrelationData.Confirm confirm;
		try {
			confirm = correlationData.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			throw new OutboxPublishException(OutboxPublishException.Reason.TIMEOUT,
					"publisher confirm timeout after %s outboxId=%s".formatted(confirmTimeout, event.getId()), ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new OutboxPublishException(OutboxPublishException.Reason.INTERRUPTED,
					"interrupted while waiting for publisher confirm outboxId=" + event.getId(), ex);
		}
		catch (ExecutionException ex) {
			throw new OutboxPublishException(OutboxPublishException.Reason.CONFIRM_FAILED,
					"publisher confirm failed outboxId=" + event.getId(), ex.getCause());
		}
		if (!confirm.ack()) {
			throw new OutboxPublishException(OutboxPublishException.Reason.NACK,
					"broker nacked message outboxId=%s reason=%s".formatted(event.getId(), confirm.reason()));
		}
		ReturnedMessage returned = correlationData.getReturned();
		if (returned != null) {
			throw new OutboxPublishException(OutboxPublishException.Reason.RETURNED,
					"message returned as unroutable outboxId=%s exchange=%s routingKey=%s replyCode=%d replyText=%s"
						.formatted(event.getId(), returned.getExchange(), returned.getRoutingKey(),
								returned.getReplyCode(), returned.getReplyText()));
		}
	}

}
