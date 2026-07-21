package com.stephanofer.networkpoints.api.request;

import com.stephanofer.networkpoints.api.amount.MonetaryAmounts;
import com.stephanofer.networkpoints.api.source.MutationContext;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * An absolute balance assignment; zero is valid.
 *
 * @param playerId the account identifier
 * @param amount the non-negative balance to assign
 * @param context the idempotency and audit context
 */
public record SetBalanceRequest(UUID playerId, BigDecimal amount, MutationContext context) {
    /**
     * Validates and creates a balance assignment request.
     *
     * @param playerId the account identifier
     * @param amount the non-negative balance with at most two decimal places
     * @param context the idempotency and audit context
     */
    public SetBalanceRequest {
        Objects.requireNonNull(playerId, "playerId");
        amount = MonetaryAmounts.nonNegative(amount, "amount");
        Objects.requireNonNull(context, "context");
    }
}
