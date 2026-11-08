package com.paymentcore.payment.event;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(@NotBlank String exchange, @NotBlank String createdQueue,
		@NotBlank String createdRoutingKey, @NotBlank String statusChangedQueue,
		@NotBlank String statusChangedRoutingKey) {
}
