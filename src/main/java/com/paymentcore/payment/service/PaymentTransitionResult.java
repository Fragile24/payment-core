package com.paymentcore.payment.service;

import com.paymentcore.payment.api.dto.PaymentResponse;

public record PaymentTransitionResult(PaymentResponse payment, PaymentTransitionOutcome outcome) {
}
