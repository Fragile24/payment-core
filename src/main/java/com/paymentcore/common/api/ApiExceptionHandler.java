package com.paymentcore.common.api;

import com.paymentcore.common.observability.RequestIdFilter;
import com.paymentcore.payment.domain.PaymentStateException;
import com.paymentcore.payment.service.IdempotencyKeyConflictException;
import com.paymentcore.payment.service.InvalidPaymentRequestException;
import com.paymentcore.payment.service.PaymentNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

	private static final String PROBLEM_TYPE_BASE = "https://paymentcore.dev/problems/";

	@ExceptionHandler(PaymentNotFoundException.class)
	ProblemDetail handlePaymentNotFound(PaymentNotFoundException ex, HttpServletRequest request) {
		return problem(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found", ex.getMessage(), request);
	}

	@ExceptionHandler(IdempotencyKeyConflictException.class)
	ProblemDetail handleIdempotencyConflict(IdempotencyKeyConflictException ex, HttpServletRequest request) {
		return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Idempotency key reused", ex.getMessage(),
				request);
	}

	@ExceptionHandler(PaymentStateException.class)
	ProblemDetail handlePaymentState(PaymentStateException ex, HttpServletRequest request) {
		ProblemDetail problem = problem(HttpStatus.CONFLICT, "INVALID_PAYMENT_STATE",
				"Invalid payment state transition", ex.getMessage(), request);
		problem.setProperty("currentStatus", ex.getCurrentStatus());
		problem.setProperty("action", ex.getAction());
		problem.setProperty("targetStatus", ex.getTargetStatus());
		return problem;
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
		return problem(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "Concurrent modification",
				"The resource was modified by another request. Retry the operation.", request);
	}

	@ExceptionHandler(InvalidPaymentRequestException.class)
	ProblemDetail handleInvalidPaymentRequest(InvalidPaymentRequestException ex, HttpServletRequest request) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_REQUEST", "Invalid payment request",
				ex.getMessage(), request);
		if (ex.getField() != null) {
			problem.setProperty("field", ex.getField());
		}
		return problem;
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail handleValidationFailure(MethodArgumentNotValidException ex, HttpServletRequest request) {
		Map<String, String> errors = new LinkedHashMap<>();
		ex.getBindingResult()
			.getFieldErrors()
			.forEach((fieldError) -> errors.merge(fieldError.getField(),
					Objects.requireNonNullElse(fieldError.getDefaultMessage(), "invalid value"),
					(first, second) -> first + "; " + second));
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed",
				"One or more request fields are invalid.", request);
		problem.setProperty("errors", errors);
		return problem;
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	ProblemDetail handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "Missing request header",
				"Required header '" + ex.getHeaderName() + "' is missing.", request);
		problem.setProperty("field", ex.getHeaderName());
		return problem;
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Invalid request parameter",
				"Parameter '" + ex.getName() + "' has an invalid value.", request);
		problem.setProperty("field", ex.getName());
		return problem;
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
		return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST_BODY", "Malformed request body",
				"The request body is missing or is not valid JSON.", request);
	}

	@ExceptionHandler(PropertyReferenceException.class)
	ProblemDetail handleInvalidSort(PropertyReferenceException ex, HttpServletRequest request) {
		return problem(HttpStatus.BAD_REQUEST, "INVALID_SORT", "Invalid sort parameter",
				"Sort property '" + ex.getPropertyName() + "' does not exist.", request);
	}

	private ProblemDetail problem(HttpStatus status, String code, String title, String detail,
			HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(title);
		problem.setType(URI.create(PROBLEM_TYPE_BASE + code.toLowerCase(Locale.ROOT).replace('_', '-')));
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("code", code);
		problem.setProperty("timestamp", Instant.now());
		String requestId = MDC.get(RequestIdFilter.MDC_KEY);
		if (requestId != null) {
			problem.setProperty("requestId", requestId);
		}
		return problem;
	}

}
