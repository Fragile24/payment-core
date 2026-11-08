package com.paymentcore.payment.outbox;

import org.springframework.amqp.AmqpException;

/**
 * Thrown when the broker did not positively confirm an outbox message.
 *
 * <p>It is an {@link AmqpException}, so the outbox publisher treats a nack, a
 * returned (unroutable) message, a confirm timeout and a connection failure the
 * same way: the attempt is recorded and the row is retried later. The
 * {@link Reason} exists so metrics and alerts can distinguish "the broker says
 * no" from "the broker is gone".</p>
 */
public class OutboxPublishException extends AmqpException {

	public enum Reason {

		/** The message could not be handed to the broker at all. */
		CONNECTION,

		/** The broker rejected the message. */
		NACK,

		/** The broker did not confirm within {@code app.outbox.confirm-timeout}. */
		TIMEOUT,

		/** The broker acked but could not route the message (returned). */
		RETURNED,

		/** The waiting thread was interrupted, usually during shutdown. */
		INTERRUPTED,

		/** The confirm completed exceptionally (connection died mid-flight). */
		CONFIRM_FAILED

	}

	private final Reason reason;

	public OutboxPublishException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public OutboxPublishException(Reason reason, String message, Throwable cause) {
		super(message, cause);
		this.reason = reason;
	}

	public Reason getReason() {
		return reason;
	}

}
