package com.paymentcore.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit trail for payment state changes.
 *
 * <p>Audit rows are written in the same database transaction as the payment
 * change so that a committed payment always has a matching audit record.</p>
 */
@Entity
@Table(name = "payment_audit_logs")
public class PaymentAuditLog {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "payment_id", nullable = false)
	private UUID paymentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, length = 32)
	private PaymentAuditEventType eventType;

	@Enumerated(EnumType.STRING)
	@Column(name = "from_status", length = 32)
	private PaymentStatus fromStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "to_status", nullable = false, length = 32)
	private PaymentStatus toStatus;

	@Column(name = "details", columnDefinition = "text")
	private String details;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected PaymentAuditLog() {
		// Required by JPA.
	}

	public static PaymentAuditLog record(UUID paymentId, PaymentAuditEventType eventType, PaymentStatus fromStatus,
			PaymentStatus toStatus, String details) {
		PaymentAuditLog log = new PaymentAuditLog();
		log.paymentId = paymentId;
		log.eventType = eventType;
		log.fromStatus = fromStatus;
		log.toStatus = toStatus;
		log.details = details;
		log.createdAt = Instant.now();
		return log;
	}

	public UUID getId() {
		return id;
	}

	public UUID getPaymentId() {
		return paymentId;
	}

	public PaymentAuditEventType getEventType() {
		return eventType;
	}

	public PaymentStatus getFromStatus() {
		return fromStatus;
	}

	public PaymentStatus getToStatus() {
		return toStatus;
	}

	public String getDetails() {
		return details;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
