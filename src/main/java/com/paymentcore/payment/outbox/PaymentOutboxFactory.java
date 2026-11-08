package com.paymentcore.payment.outbox;

import com.paymentcore.payment.domain.Payment;
import com.paymentcore.payment.domain.PaymentStatus;
import com.paymentcore.payment.event.MessagingProperties;
import com.paymentcore.payment.event.PaymentCreatedEvent;
import com.paymentcore.payment.event.PaymentEvent;
import com.paymentcore.payment.event.PaymentStatusChangedEvent;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serializes payment events into outbox rows. Serialization happens inside the
 * payment transaction, so a committed payment always has a committed event.
 */
@Component
public class PaymentOutboxFactory {

	private static final String AGGREGATE_TYPE = "PAYMENT";

	private static final String CREATED_EVENT_TYPE = "PAYMENT_CREATED";

	private static final String STATUS_CHANGED_EVENT_TYPE = "PAYMENT_STATUS_CHANGED";

	private final JsonMapper jsonMapper;

	private final MessagingProperties messagingProperties;

	public PaymentOutboxFactory(JsonMapper jsonMapper, MessagingProperties messagingProperties) {
		this.jsonMapper = jsonMapper;
		this.messagingProperties = messagingProperties;
	}

	public OutboxEvent created(Payment payment) {
		return pending(CREATED_EVENT_TYPE, messagingProperties.createdRoutingKey(), PaymentCreatedEvent.from(payment));
	}

	public OutboxEvent statusChanged(Payment payment, PaymentStatus previousStatus) {
		return pending(STATUS_CHANGED_EVENT_TYPE, messagingProperties.statusChangedRoutingKey(),
				PaymentStatusChangedEvent.from(payment, previousStatus));
	}

	private OutboxEvent pending(String eventType, String routingKey, PaymentEvent event) {
		try {
			String payload = jsonMapper.writeValueAsString(event);
			return OutboxEvent.pending(AGGREGATE_TYPE, event.paymentId(), eventType, messagingProperties.exchange(),
					routingKey, payload);
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("Could not serialize outbox event " + eventType, ex);
		}
	}

}
