package com.paymentcore.payment.service;

import com.paymentcore.payment.dto.PaymentResponse;

public record PaymentTransitionResult(PaymentResponse payment, PaymentTransitionOutcome outcome) {
}
