package com.paymentcore.ledger;

import com.paymentcore.payment.event.PaymentCreatedEvent;
import com.paymentcore.payment.event.PaymentEventType;
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
	public ProjectionOutcome handle(UUID deliveryId, PaymentEventType eventType, String payload) {
		if (!processedEvents.claim(deliveryId, CONSUMER_NAME)) {
			return ProjectionOutcome.DUPLICATE;
		}

		ProjectionRow row = switch (eventType) {
			case CREATED -> rowOf(read(payload, PaymentCreatedEvent.class));
			case STATUS_CHANGED -> rowOf(read(payload, PaymentStatusChangedEvent.class));
		};
		projection.apply(row);
		return ProjectionOutcome.PROCESSED;
	}

	private static ProjectionRow rowOf(PaymentCreatedEvent event) {
		return new ProjectionRow(event.paymentId(), event.status(), event.amount(), event.currency(),
				event.paymentVersion());
	}

	private static ProjectionRow rowOf(PaymentStatusChangedEvent event) {
		return new ProjectionRow(event.paymentId(), event.toStatus(), event.amount(), event.currency(),
				event.paymentVersion());
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
