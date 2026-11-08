package com.paymentcore.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The payment aggregate.
 *
 * <p>The length and precision constants are the Java side of the column
 * definitions in the Flyway migrations. The request DTO and the request
 * normalizer use them too, so a limit is written once.</p>
 */
@Entity
@Table(name = "payments")
public class Payment {

	public static final int AMOUNT_PRECISION = 19;

	public static final int MONEY_SCALE = 2;

	/** Integer digits that fit in {@link #AMOUNT_PRECISION} with {@link #MONEY_SCALE}. */
	public static final int AMOUNT_MAX_INTEGER_DIGITS = AMOUNT_PRECISION - MONEY_SCALE;

	public static final int MERCHANT_REFERENCE_MAX_LENGTH = 128;

	public static final int DESCRIPTION_MAX_LENGTH = 255;

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private PaymentStatus status;

	@Column(name = "amount", nullable = false, precision = AMOUNT_PRECISION, scale = MONEY_SCALE)
	private BigDecimal amount;

	@Column(name = "currency", nullable = false, length = 3)
	private String currency;

	@Column(name = "merchant_reference", length = MERCHANT_REFERENCE_MAX_LENGTH)
	private String merchantReference;

	@Column(name = "description", length = DESCRIPTION_MAX_LENGTH)
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
	 * Applies a lifecycle action. Repeating an action the payment has already
	 * reached is a replay and changes nothing; any other transition the action
	 * does not allow is rejected.
	 */
	public PaymentTransitionOutcome apply(PaymentAction action) {
		if (action.isReplayOf(this.status)) {
			return PaymentTransitionOutcome.REPLAYED;
		}
		if (!action.appliesFrom(this.status)) {
			throw new PaymentStateException(this.id, this.status, action);
		}

		Instant now = Instant.now();
		this.status = action.targetStatus();
		action.stampOn(this, now);
		this.updatedAt = now;
		return PaymentTransitionOutcome.APPLIED;
	}

	void markAuthorized(Instant at) {
		this.authorizedAt = at;
	}

	void markCaptured(Instant at) {
		this.capturedAt = at;
	}

	void markRefunded(Instant at) {
		this.refundedAt = at;
	}

	void markCancelled(Instant at) {
		this.cancelledAt = at;
	}

	void markFailed(Instant at) {
		this.failedAt = at;
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
