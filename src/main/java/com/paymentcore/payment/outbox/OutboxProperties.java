package com.paymentcore.payment.outbox;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Outbox delivery settings. {@code app.outbox.enabled} and
 * {@code app.outbox.poll-interval-ms} are read by {@link OutboxScheduler}
 * directly, so they are not bound here.
 */
@Validated
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(@Min(1) int maxAttempts, @NotNull Duration retryDelay,
		@NotNull Duration confirmTimeout, @Min(0) long failedHealthThreshold) {
}
