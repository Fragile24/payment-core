package com.paymentcore.payment.service;

import com.paymentcore.payment.api.dto.PaymentResponse;

public record PaymentCreationResult(PaymentResponse payment, PaymentCreationOutcome outcome) {
}
