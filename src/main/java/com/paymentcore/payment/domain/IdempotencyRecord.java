package com.paymentcore.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Explicit idempotency record.
 *
 * <p>A record with a stored request fingerprint and a response snapshot
 * represents a used idempotency key. The absence of a record is the "unused"
 * state. A matching fingerprint is a replay; a different fingerprint is a
 * conflict.</p>
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
	private String idempotencyKey;

	@Column(name = "request_hash", nullable = false, length = 64)
	private String requestHash;

	@Column(name = "payment_id", nullable = false)
	private UUID paymentId;

	@Column(name = "response_status", nullable = false)
	private int responseStatus;

	@Column(name = "response_body", nullable = false, columnDefinition = "text")
	private String responseBody;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected IdempotencyRecord() {
		// Required by JPA.
	}

	public static IdempotencyRecord completed(String idempotencyKey, String requestHash, UUID paymentId,
			int responseStatus, String responseBody) {
		IdempotencyRecord record = new IdempotencyRecord();
		record.idempotencyKey = idempotencyKey;
		record.requestHash = requestHash;
		record.paymentId = paymentId;
		record.responseStatus = responseStatus;
		record.responseBody = responseBody;
		record.createdAt = Instant.now();
		return record;
	}

	public boolean matches(String candidateRequestHash) {
		return this.requestHash.equals(candidateRequestHash);
	}

	public UUID getId() {
		return id;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public UUID getPaymentId() {
		return paymentId;
	}

	public int getResponseStatus() {
		return responseStatus;
	}

	public String getResponseBody() {
		return responseBody;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
