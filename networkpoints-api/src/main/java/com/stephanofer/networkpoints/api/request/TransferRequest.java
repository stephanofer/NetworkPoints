package com.stephanofer.networkpoints.api.request;

import com.stephanofer.networkpoints.api.amount.MonetaryAmounts;
import com.stephanofer.networkpoints.api.source.MutationContext;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * An atomic transfer between two distinct accounts.
 *
 * @param senderId the account debited by the transfer
 * @param recipientId the distinct account credited by the transfer
 * @param amount the strictly positive amount to transfer
 * @param context the idempotency and audit context
 */
public record TransferRequest(
        UUID senderId, UUID recipientId, BigDecimal amount, MutationContext context) {
    /**
     * Validates and creates a transfer request.
     *
     * @param senderId the account debited by the transfer
     * @param recipientId the distinct account credited by the transfer
     * @param amount the strictly positive amount with at most two decimal places
     * @param context the idempotency and audit context
     */
    public TransferRequest {
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(recipientId, "recipientId");
        if (senderId.equals(recipientId)) {
            throw new IllegalArgumentException("senderId and recipientId must be different");
        }
        amount = MonetaryAmounts.positive(amount, "amount");
        Objects.requireNonNull(context, "context");
    }
}
