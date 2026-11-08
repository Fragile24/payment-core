package com.paymentcore.payment.service;

import com.paymentcore.payment.dto.PaymentResponse;

public record PaymentCreationResult(PaymentResponse payment, PaymentCreationOutcome outcome) {
}
