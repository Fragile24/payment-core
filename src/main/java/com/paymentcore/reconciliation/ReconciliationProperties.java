package com.paymentcore.reconciliation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Reconciliation settings. {@code app.reconciliation.enabled},
 * {@code app.reconciliation.interval-ms} and
 * {@code app.reconciliation.initial-delay-ms} are read by
 * {@link ReconciliationScheduler} directly, so they are not bound here.
 */
@Validated
@ConfigurationProperties(prefix = "app.reconciliation")
public record ReconciliationProperties(@Min(1) int batchSize, @NotNull Duration outboxStuckAfter,
		@NotNull Duration lockTtl) {
}
