package com.paymentcore.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private PaymentStatus status;

	@Column(name = "amount", nullable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	@Column(name = "currency", nullable = false, length = 3)
	private String currency;

	@Column(name = "merchant_reference", length = 128)
	private String merchantReference;

	@Column(name = "description", length = 255)
	private String description;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "authorized_at")
	private Instant authorizedAt;

	@Column(name = "captured_at")
	private Instant capturedAt;

	@Column(name = "refunded_at")
	private Instant refundedAt;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	@Column(name = "failed_at")
	private Instant failedAt;

	protected Payment() {
		// Required by JPA.
	}

	public static Payment initiate(BigDecimal amount, String currency, String merchantReference, String description) {
		Payment payment = new Payment();
		payment.status = PaymentStatus.INITIATED;
		payment.amount = amount;
		payment.currency = currency;
		payment.merchantReference = merchantReference;
		payment.description = description;
		payment.createdAt = Instant.now();
		payment.updatedAt = payment.createdAt;
		return payment;
	}

	/**
	 * Applies a lifecycle action through the explicit state machine.
	 * Callers must handle the idempotent "already in target state" case first.
	 */
	public void apply(PaymentAction action) {
		if (!PaymentStateMachine.canApply(action, this.status)) {
			throw new PaymentStateException(this.id, this.status, action);
		}

		this.status = action.targetStatus();
		Instant now = Instant.now();
		switch (action) {
			case AUTHORIZE -> this.authorizedAt = now;
			case CAPTURE -> this.capturedAt = now;
			case REFUND -> this.refundedAt = now;
			case CANCEL -> this.cancelledAt = now;
			case FAIL -> this.failedAt = now;
		}
		this.updatedAt = now;
	}

	@PreUpdate
	void markUpdated() {
		this.updatedAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public PaymentStatus getStatus() {
		return status;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getCurrency() {
		return currency;
	}

	public String getMerchantReference() {
		return merchantReference;
	}

	public String getDescription() {
		return description;
	}

	public long getVersion() {
		return version;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Instant getAuthorizedAt() {
		return authorizedAt;
	}

	public Instant getCapturedAt() {
		return capturedAt;
	}

	public Instant getRefundedAt() {
		return refundedAt;
	}

	public Instant getCancelledAt() {
		return cancelledAt;
	}

	public Instant getFailedAt() {
		return failedAt;
	}

}
