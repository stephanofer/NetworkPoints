package com.stephanofer.networkpoints.api.result;

import com.stephanofer.networkpoints.api.amount.MonetaryAmounts;
import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable result of an atomic transfer.
 *
 * <p>All optional values are present for {@link MutationStatus#SUCCESS}. A rejected result may omit
 * values that were not established. Successful results are returned only after both account changes
 * have committed atomically.</p>
 *
 * @param status the business outcome
 * @param operationId the request's idempotency key
 * @param senderId the debited account identifier
 * @param recipientId the credited account identifier
 * @param senderBefore the sender's committed pre-transfer snapshot, or empty when unavailable
 * @param senderAfter the sender's committed post-transfer snapshot, or empty when unavailable
 * @param recipientBefore the recipient's committed pre-transfer snapshot, or empty when unavailable
 * @param recipientAfter the recipient's committed post-transfer snapshot, or empty when unavailable
 * @param amount the transferred amount, or empty when not established
 * @param senderDelta the sender's signed balance change, or empty when not established
 * @param recipientDelta the recipient's signed balance change, or empty when not established
 * @param baseAmount the transfer amount before multiplier application, or empty when not established
 * @param multiplier the applied multiplier, or empty when not established
 * @param finalAmount the effective transferred amount, or empty when not established
 * @param replayed whether this success reproduces a previously committed idempotent operation
 */
public record TransferResult(
        MutationStatus status,
        UUID operationId,
        UUID senderId,
        UUID recipientId,
        Optional<BalanceSnapshot> senderBefore,
        Optional<BalanceSnapshot> senderAfter,
        Optional<BalanceSnapshot> recipientBefore,
        Optional<BalanceSnapshot> recipientAfter,
        Optional<BigDecimal> amount,
        Optional<BigDecimal> senderDelta,
        Optional<BigDecimal> recipientDelta,
        Optional<BigDecimal> baseAmount,
        Optional<BigDecimal> multiplier,
        Optional<BigDecimal> finalAmount,
        boolean replayed) {
    /**
     * Validates and creates a transfer result.
     *
     * @param status the business outcome
     * @param operationId the request's idempotency key
     * @param senderId the debited account identifier
     * @param recipientId the credited account identifier
     * @param senderBefore the sender's pre-transfer snapshot, if established
     * @param senderAfter the sender's post-transfer snapshot, if established
     * @param recipientBefore the recipient's pre-transfer snapshot, if established
     * @param recipientAfter the recipient's post-transfer snapshot, if established
     * @param amount the transferred amount, if established
     * @param senderDelta the sender's signed balance change, if established
     * @param recipientDelta the recipient's signed balance change, if established
     * @param baseAmount the transfer amount before multiplier application, if established
     * @param multiplier the applied multiplier, if established
     * @param finalAmount the effective transferred amount, if established
     * @param replayed whether this success reproduces an idempotent operation
     */
    public TransferResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(recipientId, "recipientId");
        if (senderId.equals(recipientId)) {
            throw new IllegalArgumentException("senderId and recipientId must be different");
        }
        senderBefore = Objects.requireNonNull(senderBefore, "senderBefore");
        senderAfter = Objects.requireNonNull(senderAfter, "senderAfter");
        recipientBefore = Objects.requireNonNull(recipientBefore, "recipientBefore");
        recipientAfter = Objects.requireNonNull(recipientAfter, "recipientAfter");
        amount = Objects.requireNonNull(amount, "amount")
                .map(value -> MonetaryAmounts.positive(value, "amount"));
        senderDelta = Objects.requireNonNull(senderDelta, "senderDelta")
                .map(value -> MonetaryAmounts.signed(value, "senderDelta"));
        recipientDelta = Objects.requireNonNull(recipientDelta, "recipientDelta")
                .map(value -> MonetaryAmounts.signed(value, "recipientDelta"));
        baseAmount = Objects.requireNonNull(baseAmount, "baseAmount")
                .map(value -> MonetaryAmounts.positive(value, "baseAmount"));
        multiplier = Objects.requireNonNull(multiplier, "multiplier")
                .map(value -> MonetaryAmounts.multiplier(value, "multiplier"));
        finalAmount = Objects.requireNonNull(finalAmount, "finalAmount")
                .map(value -> MonetaryAmounts.positive(value, "finalAmount"));
        if (status == MutationStatus.SUCCESS) {
            requirePresent(senderBefore, "senderBefore");
            requirePresent(senderAfter, "senderAfter");
            requirePresent(recipientBefore, "recipientBefore");
            requirePresent(recipientAfter, "recipientAfter");
            requirePresent(amount, "amount");
            requirePresent(senderDelta, "senderDelta");
            requirePresent(recipientDelta, "recipientDelta");
            requirePresent(baseAmount, "baseAmount");
            requirePresent(multiplier, "multiplier");
            requirePresent(finalAmount, "finalAmount");
            BalanceSnapshot sourceBefore = senderBefore.orElseThrow();
            BalanceSnapshot sourceAfter = senderAfter.orElseThrow();
            BalanceSnapshot targetBefore = recipientBefore.orElseThrow();
            BalanceSnapshot targetAfter = recipientAfter.orElseThrow();
            BigDecimal transferred = amount.orElseThrow();
            if (!sourceBefore.playerId().equals(senderId) || !sourceAfter.playerId().equals(senderId)) {
                throw new IllegalArgumentException("sender snapshots must belong to senderId");
            }
            if (!targetBefore.playerId().equals(recipientId)
                    || !targetAfter.playerId().equals(recipientId)) {
                throw new IllegalArgumentException("recipient snapshots must belong to recipientId");
            }
            if (sourceAfter.revision() <= sourceBefore.revision()
                    || targetAfter.revision() <= targetBefore.revision()) {
                throw new IllegalArgumentException("after revisions must be greater than before revisions");
            }
            if (sourceAfter.balance().subtract(sourceBefore.balance())
                            .compareTo(senderDelta.orElseThrow())
                    != 0
                    || targetAfter.balance().subtract(targetBefore.balance())
                                    .compareTo(recipientDelta.orElseThrow())
                            != 0
                    || senderDelta.orElseThrow().compareTo(transferred.negate()) != 0
                    || recipientDelta.orElseThrow().compareTo(transferred) != 0
                    || baseAmount.orElseThrow().compareTo(transferred) != 0
                    || multiplier.orElseThrow().compareTo(BigDecimal.ONE) != 0
                    || finalAmount.orElseThrow().compareTo(transferred) != 0) {
                throw new IllegalArgumentException("deltas and snapshots must match the transferred amount");
            }
        } else if (replayed) {
            throw new IllegalArgumentException("only a successful result can be replayed");
        }
    }

    /**
     * Reports whether the transfer committed successfully.
     *
     * @return {@code true} exactly when {@link #status()} is {@link MutationStatus#SUCCESS}
     */
    public boolean success() {
        return status == MutationStatus.SUCCESS;
    }

    private static void requirePresent(Optional<?> value, String name) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required for a successful result");
        }
    }
}
