package com.paymentcore.payment.dto;

import com.paymentcore.payment.domain.Payment;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Payload used to initiate a payment.
 *
 * <p>The annotations reject shape errors at the HTTP edge with field-level
 * messages; the limits they use are the ones the aggregate stores.</p>
 */
@Schema(description = "Payload used to initiate a payment.")
public record CreatePaymentRequest(

		@NotNull(message = "amount is required")
		@DecimalMin(value = "0.01", message = "amount must be at least {value}")
		@Digits(integer = Payment.AMOUNT_MAX_INTEGER_DIGITS, fraction = Payment.MONEY_SCALE,
				message = "amount must have at most {fraction} decimal places")
		@Schema(description = "Amount in major currency units, with up to 2 decimal places.", example = "125.50")
		BigDecimal amount,

		@NotBlank(message = "currency is required")
		@Pattern(regexp = "[A-Za-z]{3}", message = "currency must be a 3-letter ISO 4217 code")
		@Schema(description = "ISO 4217 currency code.", example = "USD")
		String currency,

		@Size(max = Payment.MERCHANT_REFERENCE_MAX_LENGTH,
				message = "merchantReference must be at most {max} characters")
		@Schema(description = "Optional merchant-side reference.", example = "ORDER-2026-0001")
		String merchantReference,

		@Size(max = Payment.DESCRIPTION_MAX_LENGTH, message = "description must be at most {max} characters")
		@Schema(description = "Optional human-readable description.", example = "Sandbox order payment")
		String description

) {
}
