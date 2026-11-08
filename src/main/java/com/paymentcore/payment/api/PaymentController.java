package com.paymentcore.payment.api;

import com.paymentcore.common.api.PageResponse;
import com.paymentcore.payment.domain.PaymentAction;
import com.paymentcore.payment.domain.PaymentTransitionOutcome;
import com.paymentcore.payment.dto.CreatePaymentRequest;
import com.paymentcore.payment.dto.PaymentAuditResponse;
import com.paymentcore.payment.dto.PaymentResponse;
import com.paymentcore.payment.service.PaymentCreationOutcome;
import com.paymentcore.payment.service.PaymentCreationResult;
import com.paymentcore.payment.service.PaymentRequestNormalizer;
import com.paymentcore.payment.service.PaymentService;
import com.paymentcore.payment.service.PaymentTransitionResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Create, transition, and inspect payments")
public class PaymentController {

	private final PaymentService paymentService;

	public PaymentController(PaymentService paymentService) {
		this.paymentService = paymentService;
	}

	@PostMapping
	@Operation(summary = "Create a payment",
			description = "Creates a payment idempotently. Retrying with the same Idempotency-Key and the same "
					+ "payload returns the original payment with HTTP 200; reusing the key with a different payload "
					+ "returns HTTP 409.")
	@ApiResponses({ @ApiResponse(responseCode = "201", description = "Payment created"),
			@ApiResponse(responseCode = "200", description = "Idempotent replay of an existing payment"),
			@ApiResponse(responseCode = "400", description = "Invalid request"),
			@ApiResponse(responseCode = "409", description = "Idempotency key reused with a different payload") })
	public ResponseEntity<PaymentResponse> create(
			@Parameter(description = "Client-generated unique key for this request.",
					required = true, example = "checkout-7f3c2f1e-4d4a-4b9b-9f3e-1a2b3c4d5e6f")
			@RequestHeader(PaymentRequestNormalizer.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
			@Valid @RequestBody CreatePaymentRequest request) {
		PaymentCreationResult result = paymentService.create(idempotencyKey, request);
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
			.path("/{paymentId}")
			.buildAndExpand(result.payment().id())
			.toUri();
		boolean replayed = result.outcome() == PaymentCreationOutcome.REPLAYED;
		HttpStatus status = replayed ? HttpStatus.OK : HttpStatus.CREATED;
		return ResponseEntity.status(status)
			.location(location)
			.header("Idempotent-Replayed", Boolean.toString(replayed))
			.header("Idempotency-Outcome", result.outcome().name())
			.body(result.payment());
	}

	@PostMapping("/{paymentId}/authorize")
	@Operation(summary = "Authorize a payment",
			description = "Moves the payment to AUTHORIZED. Repeating the call once the payment is authorized is "
					+ "idempotent and returns the current payment.")
	@ApiResponses({ @ApiResponse(responseCode = "200", description = "Payment authorized or already authorized"),
			@ApiResponse(responseCode = "404", description = "Payment not found"),
			@ApiResponse(responseCode = "409", description = "Invalid payment state transition") })
	public ResponseEntity<PaymentResponse> authorize(@PathVariable UUID paymentId) {
		return transition(paymentId, PaymentAction.AUTHORIZE);
	}

	@PostMapping("/{paymentId}/capture")
	@Operation(summary = "Capture a payment",
			description = "Moves the payment to CAPTURED. Repeating the call once the payment is captured is "
					+ "idempotent and returns the current payment.")
	@ApiResponses({ @ApiResponse(responseCode = "200", description = "Payment captured or already captured"),
			@ApiResponse(responseCode = "404", description = "Payment not found"),
			@ApiResponse(responseCode = "409", description = "Invalid payment state transition") })
	public ResponseEntity<PaymentResponse> capture(@PathVariable UUID paymentId) {
		return transition(paymentId, PaymentAction.CAPTURE);
	}

	@PostMapping("/{paymentId}/refund")
	@Operation(summary = "Refund a payment",
			description = "Moves the payment to REFUNDED. This iteration performs a full refund. Repeating the call "
					+ "once the payment is refunded is idempotent and returns the current payment.")
	@ApiResponses({ @ApiResponse(responseCode = "200", description = "Payment refunded or already refunded"),
			@ApiResponse(responseCode = "404", description = "Payment not found"),
			@ApiResponse(responseCode = "409", description = "Invalid payment state transition") })
	public ResponseEntity<PaymentResponse> refund(@PathVariable UUID paymentId) {
		return transition(paymentId, PaymentAction.REFUND);
	}

	@PostMapping("/{paymentId}/cancel")
	@Operation(summary = "Cancel a payment",
			description = "Moves the payment to CANCELLED. Repeating the call once the payment is cancelled is "
					+ "idempotent and returns the current payment.")
	@ApiResponses({ @ApiResponse(responseCode = "200", description = "Payment cancelled or already cancelled"),
			@ApiResponse(responseCode = "404", description = "Payment not found"),
			@ApiResponse(responseCode = "409", description = "Invalid payment state transition") })
	public ResponseEntity<PaymentResponse> cancel(@PathVariable UUID paymentId) {
		return transition(paymentId, PaymentAction.CANCEL);
	}

	@PostMapping("/{paymentId}/fail")
	@Operation(summary = "Fail a payment",
			description = "Moves the payment to FAILED. This endpoint exists to simulate processor or downstream "
					+ "failure in the sandbox. Repeating the call once the payment failed is idempotent and returns "
					+ "the current payment.")
	@ApiResponses({ @ApiResponse(responseCode = "200", description = "Payment failed or already failed"),
			@ApiResponse(responseCode = "404", description = "Payment not found"),
			@ApiResponse(responseCode = "409", description = "Invalid payment state transition") })
	public ResponseEntity<PaymentResponse> fail(@PathVariable UUID paymentId) {
		return transition(paymentId, PaymentAction.FAIL);
	}

	@GetMapping("/{paymentId}")
	@Operation(summary = "Get a payment by id")
	@ApiResponses({ @ApiResponse(responseCode = "200", description = "Payment found"),
			@ApiResponse(responseCode = "404", description = "Payment not found") })
	public PaymentResponse get(@PathVariable UUID paymentId) {
		return paymentService.get(paymentId);
	}

	@GetMapping
	@Operation(summary = "List payments", description = "Returns a paginated list of payments, newest first.")
	public PageResponse<PaymentResponse> list(
			@ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return PageResponse.from(paymentService.list(pageable));
	}

	@GetMapping("/{paymentId}/audit")
	@Operation(summary = "Get the audit trail for a payment")
	@ApiResponses({ @ApiResponse(responseCode = "200", description = "Audit trail returned"),
			@ApiResponse(responseCode = "404", description = "Payment not found") })
	public List<PaymentAuditResponse> audit(@PathVariable UUID paymentId) {
		return paymentService.getAuditTrail(paymentId);
	}

	private ResponseEntity<PaymentResponse> transition(UUID paymentId, PaymentAction action) {
		PaymentTransitionResult result = paymentService.transition(paymentId, action);
		boolean replayed = result.outcome() == PaymentTransitionOutcome.REPLAYED;
		return ResponseEntity.ok()
			.header("Idempotent-Replayed", Boolean.toString(replayed))
			.header("Transition-Outcome", result.outcome().name())
			.body(result.payment());
	}

}
