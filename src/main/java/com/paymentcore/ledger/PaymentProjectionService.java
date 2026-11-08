package com.paymentcore.ledger;

import com.paymentcore.payment.event.PaymentCreatedEvent;
import com.paymentcore.payment.event.PaymentStatusChangedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Applies one payment event to the read model.
 *
 * <p>The transaction covers the delivery record and the projection write. Either
 * both happen or neither does, so a retry after a crash re-applies the event
 * instead of losing it, and a duplicate delivery is detected before it can
 * rewrite the projection.</p>
 */
@Service
public class PaymentProjectionService {

	static final String CONSUMER_NAME = "payment-projection-v1";

	static final String CREATED_EVENT = "PAYMENT_CREATED";

	static final String STATUS_CHANGED_EVENT = "PAYMENT_STATUS_CHANGED";

	private final ProcessedEventStore processedEvents;

	private final PaymentProjectionStore projection;

	private final JsonMapper jsonMapper;

	public PaymentProjectionService(ProcessedEventStore processedEvents, PaymentProjectionStore projection,
			JsonMapper jsonMapper) {
		this.processedEvents = processedEvents;
		this.projection = projection;
		this.jsonMapper = jsonMapper;
	}

	@Transactional
	public ProjectionOutcome handle(UUID deliveryId, String eventType, String payload) {
		if (processedEvents.insertIfAbsent(deliveryId, CONSUMER_NAME) == 0) {
			return ProjectionOutcome.DUPLICATE;
		}

		switch (eventType) {
			case CREATED_EVENT -> {
				PaymentCreatedEvent event = read(payload, PaymentCreatedEvent.class);
				projection.apply(event.paymentId(), event.status(), event.amount(), event.currency(),
						event.paymentVersion(), deliveryId);
			}
			case STATUS_CHANGED_EVENT -> {
				PaymentStatusChangedEvent event = read(payload, PaymentStatusChangedEvent.class);
				projection.apply(event.paymentId(), event.toStatus(), event.amount(), event.currency(),
						event.paymentVersion(), deliveryId);
			}
			default -> throw new IllegalArgumentException("Unsupported payment event type: " + eventType);
		}
		return ProjectionOutcome.PROCESSED;
	}

	private <T> T read(String payload, Class<T> eventType) {
		try {
			return jsonMapper.readValue(payload, eventType);
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("Could not deserialize " + eventType.getSimpleName(), ex);
		}
	}

}
