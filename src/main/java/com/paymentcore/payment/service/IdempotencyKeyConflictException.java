package com.paymentcore.payment.service;

public class IdempotencyKeyConflictException extends RuntimeException {

	public IdempotencyKeyConflictException(String idempotencyKey) {
		super("Idempotency-Key '" + idempotencyKey + "' was already used with a different request payload.");
	}

}
