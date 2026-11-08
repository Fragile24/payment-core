package com.paymentcore.ledger;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The source-of-truth values the {@code payment_projection} read model mirrors.
 *
 * <p>One type is used by the consumer that builds the row, the repair that
 * rebuilds it and the drift check that compares it, so the mirrored column set
 * is written once.</p>
 */
public record ProjectionRow(UUID paymentId, String status, BigDecimal amount, String currency, long version) {
}
