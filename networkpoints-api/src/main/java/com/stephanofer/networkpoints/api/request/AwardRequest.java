package com.stephanofer.networkpoints.api.request;

import com.stephanofer.networkpoints.api.amount.MonetaryAmounts;
import com.stephanofer.networkpoints.api.source.MutationContext;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * A booster-eligible points award.
 *
 * @param playerId the recipient account identifier
 * @param amount the strictly positive base amount, before boosters
 * @param gameId the non-blank game identifier used for booster evaluation
 * @param serverId the non-blank server identifier used for booster evaluation
 * @param context the idempotency and audit context
 */
public record AwardRequest(
        UUID playerId,
        BigDecimal amount,
        String gameId,
        String serverId,
        MutationContext context) {
    /**
     * Validates and creates an award request.
     *
     * @param playerId the recipient account identifier
     * @param amount the strictly positive base amount with at most two decimal places
     * @param gameId the non-blank game identifier
     * @param serverId the non-blank server identifier
     * @param context the idempotency and audit context
     */
    public AwardRequest {
        Objects.requireNonNull(playerId, "playerId");
        amount = MonetaryAmounts.positive(amount, "amount");
        requireText(gameId, "gameId");
        requireText(serverId, "serverId");
        Objects.requireNonNull(context, "context");
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > 64) {
            throw new IllegalArgumentException(name + " must not exceed 64 characters");
        }
        if (!value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e)) {
            throw new IllegalArgumentException(name + " must contain printable ASCII characters only");
        }
    }
}
