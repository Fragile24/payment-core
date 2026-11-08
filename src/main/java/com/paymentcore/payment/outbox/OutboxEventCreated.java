package com.paymentcore.payment.outbox;

import java.util.UUID;

public record OutboxEventCreated(UUID outboxEventId) {
}
