package com.paymentcore.payment.outbox;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(boolean enabled, @Min(1) int maxAttempts, @NotNull Duration retryDelay,
		@Min(100) long pollIntervalMs, @NotNull Duration confirmTimeout) {
}
