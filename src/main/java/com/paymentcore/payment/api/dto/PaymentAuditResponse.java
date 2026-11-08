package com.paymentcore.payment.api.dto;

import com.paymentcore.payment.domain.PaymentAuditEventType;
import com.paymentcore.payment.domain.PaymentAuditLog;
import com.paymentcore.payment.domain.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

public record PaymentAuditResponse(UUID id, PaymentAuditEventType eventType, PaymentStatus fromStatus,
		PaymentStatus toStatus, String details, Instant createdAt) {

	public static PaymentAuditResponse from(PaymentAuditLog auditLog) {
		return new PaymentAuditResponse(auditLog.getId(), auditLog.getEventType(), auditLog.getFromStatus(),
				auditLog.getToStatus(), auditLog.getDetails(), auditLog.getCreatedAt());
	}

}
