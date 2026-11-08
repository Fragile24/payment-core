package com.paymentcore.payment.outbox;

import com.paymentcore.payment.domain.Payment;
import com.paymentcore.payment.domain.PaymentStatus;
import com.paymentcore.payment.event.MessagingProperties;
import com.paymentcore.payment.event.PaymentCreatedEvent;
import com.paymentcore.payment.event.PaymentEvent;
import com.paymentcore.payment.event.PaymentEventType;
import com.paymentcore.payment.event.PaymentStatusChangedEvent;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps a payment to its integration event and serializes it into an outbox row.
 *
 * <p>Serialization happens inside the payment transaction, so a committed
 * payment always has a committed event.</p>
 */
@Component
public class PaymentOutboxFactory {

	private static final String AGGREGATE_TYPE = "PAYMENT";

	private final JsonMapper jsonMapper;

	private final MessagingProperties messagingProperties;

	public PaymentOutboxFactory(JsonMapper jsonMapper, MessagingProperties messagingProperties) {
		this.jsonMapper = jsonMapper;
		this.messagingProperties = messagingProperties;
	}

	public OutboxEvent created(Payment payment) {
		return pending(PaymentEventType.CREATED, messagingProperties.createdRoutingKey(), new PaymentCreatedEvent(
				UUID.randomUUID(), payment.getId(), payment.getAmount(), payment.getCurrency(),
				payment.getMerchantReference(), payment.getStatus().name(), payment.getVersion(), Instant.now()));
	}

	public OutboxEvent statusChanged(Payment payment, PaymentStatus previousStatus) {
		return pending(PaymentEventType.STATUS_CHANGED, messagingProperties.statusChangedRoutingKey(),
				new PaymentStatusChangedEvent(UUID.randomUUID(), payment.getId(), previousStatus.name(),
						payment.getStatus().name(), payment.getAmount(), payment.getCurrency(), payment.getVersion(),
						Instant.now()));
	}

	private OutboxEvent pending(PaymentEventType eventType, String routingKey, PaymentEvent event) {
		try {
			String payload = jsonMapper.writeValueAsString(event);
			return OutboxEvent.pending(AGGREGATE_TYPE, event.paymentId(), eventType.wireName(),
					messagingProperties.exchange(), routingKey, payload);
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("Could not serialize outbox event " + eventType.wireName(), ex);
		}
	}

}
