package com.paymentcore.reconciliation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.reconciliation")
public record ReconciliationProperties(boolean enabled, @Min(1000) long intervalMs, @Min(0) long initialDelayMs,
		@Min(1) int batchSize, @NotNull Duration outboxStuckAfter, @NotNull Duration lockTtl) {
}
