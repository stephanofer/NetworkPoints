package com.stephanofer.networkpoints.api.balance;

import com.stephanofer.networkpoints.api.amount.MonetaryAmounts;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable, revisioned view of an account balance.
 *
 * @param playerId the account owner's unique identifier
 * @param balance the non-negative balance, normalized to scale two
 * @param revision the monotonically increasing, non-negative account revision
 */
public record BalanceSnapshot(UUID playerId, BigDecimal balance, long revision) {
    /**
     * Validates and creates a balance snapshot.
     *
     * @param playerId the account owner's unique identifier
     * @param balance the non-negative balance with at most two decimal places
     * @param revision the non-negative account revision
     */
    public BalanceSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        balance = MonetaryAmounts.nonNegative(balance, "balance");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
    }
}
