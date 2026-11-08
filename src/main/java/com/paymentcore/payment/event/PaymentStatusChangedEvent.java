package com.paymentcore.payment.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted for every applied lifecycle transition.
 *
 * <p>{@code paymentVersion} is the aggregate version after the transition, so
 * consumers can reject stale deliveries instead of moving the projection
 * backwards.</p>
 *
 * <p>{@code fromStatus} and {@code toStatus} are the wire names of the statuses,
 * so a consumer of the event does not depend on the domain enum.</p>
 */
public record PaymentStatusChangedEvent(UUID eventId, UUID paymentId, String fromStatus, String toStatus,
		BigDecimal amount, String currency, long paymentVersion, Instant occurredAt) implements PaymentEvent {
}
