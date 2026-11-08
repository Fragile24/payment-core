package com.paymentcore.payment.service;

public class InvalidPaymentRequestException extends RuntimeException {

	private final String field;

	public InvalidPaymentRequestException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String getField() {
		return field;
	}

}
