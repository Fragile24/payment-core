package com.paymentcore.payment.outbox;

import org.springframework.amqp.AmqpException;

/**
 * Thrown when the broker did not positively confirm an outbox message.
 *
 * <p>It is an {@link AmqpException}, so the outbox publisher treats a nack, a
 * returned (unroutable) message, a confirm timeout and a connection failure
 * the same way: the attempt is recorded and the row is retried later.</p>
 */
public class OutboxPublishException extends AmqpException {

	public OutboxPublishException(String message) {
		super(message);
	}

	public OutboxPublishException(String message, Throwable cause) {
		super(message, cause);
	}

}
