package com.paymentcore.payment.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = "Payload used to initiate a payment.")
public record CreatePaymentRequest(

		@NotNull(message = "amount is required")
		@DecimalMin(value = "0.01", message = "amount must be at least 0.01")
		@Digits(integer = 17, fraction = 2, message = "amount must have at most 2 decimal places")
		@Schema(description = "Amount in major currency units, with up to 2 decimal places.", example = "125.50")
		BigDecimal amount,

		@NotBlank(message = "currency is required")
		@Pattern(regexp = "[A-Za-z]{3}", message = "currency must be a 3-letter ISO 4217 code")
		@Schema(description = "ISO 4217 currency code.", example = "USD")
		String currency,

		@Size(max = 128, message = "merchantReference must be at most 128 characters")
		@Schema(description = "Optional merchant-side reference.", example = "ORDER-2026-0001")
		String merchantReference,

		@Size(max = 255, message = "description must be at most 255 characters")
		@Schema(description = "Optional human-readable description.", example = "Sandbox order payment")
		String description

) {
}
