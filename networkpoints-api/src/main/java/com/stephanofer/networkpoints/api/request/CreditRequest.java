package com.stephanofer.networkpoints.api.request;

import com.stephanofer.networkpoints.api.amount.MonetaryAmounts;
import com.stephanofer.networkpoints.api.source.MutationContext;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * A positive, non-booster points credit.
 *
 * @param playerId the account identifier
 * @param amount the strictly positive amount to credit
 * @param context the idempotency and audit context
 */
public record CreditRequest(UUID playerId, BigDecimal amount, MutationContext context) {
    /**
     * Validates and creates a credit request.
     *
     * @param playerId the account identifier
     * @param amount the strictly positive amount with at most two decimal places
     * @param context the idempotency and audit context
     */
    public CreditRequest {
        Objects.requireNonNull(playerId, "playerId");
        amount = MonetaryAmounts.positive(amount, "amount");
        Objects.requireNonNull(context, "context");
    }
}
