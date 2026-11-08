package com.paymentcore.payment.api;

import com.paymentcore.common.api.ApiExceptionHandler;
import com.paymentcore.payment.domain.PaymentStateException;
import com.paymentcore.payment.service.IdempotencyKeyConflictException;
import com.paymentcore.payment.service.InvalidPaymentRequestException;
import com.paymentcore.payment.service.PaymentNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PaymentExceptionHandler {

	@ExceptionHandler(PaymentNotFoundException.class)
	ProblemDetail handlePaymentNotFound(PaymentNotFoundException ex, HttpServletRequest request) {
		return ApiExceptionHandler.problem(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found",
				ex.getMessage(), request);
	}

	@ExceptionHandler(IdempotencyKeyConflictException.class)
	ProblemDetail handleIdempotencyConflict(IdempotencyKeyConflictException ex, HttpServletRequest request) {
		return ApiExceptionHandler.problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Idempotency key reused",
				ex.getMessage(), request);
	}

	@ExceptionHandler(PaymentStateException.class)
	ProblemDetail handlePaymentState(PaymentStateException ex, HttpServletRequest request) {
		ProblemDetail problem = ApiExceptionHandler.problem(HttpStatus.CONFLICT, "INVALID_PAYMENT_STATE",
				"Invalid payment state transition", ex.getMessage(), request);
		problem.setProperty("currentStatus", ex.getCurrentStatus());
		problem.setProperty("action", ex.getAction());
		problem.setProperty("targetStatus", ex.getTargetStatus());
		return problem;
	}

	@ExceptionHandler(InvalidPaymentRequestException.class)
	ProblemDetail handleInvalidPaymentRequest(InvalidPaymentRequestException ex, HttpServletRequest request) {
		ProblemDetail problem = ApiExceptionHandler.problem(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_REQUEST",
				"Invalid payment request", ex.getMessage(), request);
		if (ex.getField() != null) {
			problem.setProperty("field", ex.getField());
		}
		return problem;
	}

}
