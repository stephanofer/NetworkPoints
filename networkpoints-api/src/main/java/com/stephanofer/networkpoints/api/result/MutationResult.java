package com.stephanofer.networkpoints.api.result;

import com.stephanofer.networkpoints.api.amount.MonetaryAmounts;
import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable result of an award, credit, debit, or balance assignment.
 *
 * <p>All optional values are present for {@link MutationStatus#SUCCESS}. A rejected result may omit
 * values that were not established. Successful results are returned only after the mutation has
 * committed.</p>
 *
 * @param status the business outcome
 * @param type the requested mutation type
 * @param operationId the request's idempotency key
 * @param playerId the affected account identifier
 * @param before the committed pre-mutation snapshot, or empty when unavailable
 * @param after the committed post-mutation snapshot, or empty when unavailable
 * @param delta the signed balance change, or empty when not established
 * @param baseAmount the amount before multiplier application, or empty when not established
 * @param multiplier the applied multiplier, or empty when not established
 * @param finalAmount the effective unsigned amount, or empty when not established
 * @param replayed whether this success reproduces a previously committed idempotent operation
 */
public record MutationResult(
        MutationStatus status,
        MutationType type,
        UUID operationId,
        UUID playerId,
        Optional<BalanceSnapshot> before,
        Optional<BalanceSnapshot> after,
        Optional<BigDecimal> delta,
        Optional<BigDecimal> baseAmount,
        Optional<BigDecimal> multiplier,
        Optional<BigDecimal> finalAmount,
        boolean replayed) {
    /**
     * Validates and creates a mutation result.
     *
     * @param status the business outcome
     * @param type the requested mutation type
     * @param operationId the request's idempotency key
     * @param playerId the affected account identifier
     * @param before the pre-mutation snapshot, if established
     * @param after the post-mutation snapshot, if established
     * @param delta the signed balance change, if established
     * @param baseAmount the amount before multiplier application, if established
     * @param multiplier the applied multiplier, if established
     * @param finalAmount the effective unsigned amount, if established
     * @param replayed whether this success reproduces an idempotent operation
     */
    public MutationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        before = Objects.requireNonNull(before, "before");
        after = Objects.requireNonNull(after, "after");
        delta = normalize(delta, true, "delta");
        baseAmount = normalize(baseAmount, false, "baseAmount");
        finalAmount = normalize(finalAmount, false, "finalAmount");
        multiplier = Objects.requireNonNull(multiplier, "multiplier")
                .map(value -> MonetaryAmounts.multiplier(value, "multiplier"));
        if (status == MutationStatus.SUCCESS) {
            requirePresent(before, "before");
            requirePresent(after, "after");
            requirePresent(delta, "delta");
            requirePresent(baseAmount, "baseAmount");
            requirePresent(multiplier, "multiplier");
            requirePresent(finalAmount, "finalAmount");
            BalanceSnapshot previous = before.orElseThrow();
            BalanceSnapshot current = after.orElseThrow();
            if (!previous.playerId().equals(playerId) || !current.playerId().equals(playerId)) {
                throw new IllegalArgumentException("snapshots must belong to playerId");
            }
            if (current.revision() <= previous.revision()) {
                throw new IllegalArgumentException("after revision must be greater than before revision");
            }
            if (current.balance().subtract(previous.balance()).compareTo(delta.orElseThrow()) != 0) {
                throw new IllegalArgumentException("delta must match the balance change");
            }
            validateBreakdown(
                    type,
                    current,
                    delta.orElseThrow(),
                    baseAmount.orElseThrow(),
                    multiplier.orElseThrow(),
                    finalAmount.orElseThrow());
        } else if (replayed) {
            throw new IllegalArgumentException("only a successful result can be replayed");
        }
    }

    /**
     * Reports whether the mutation committed successfully.
     *
     * @return {@code true} exactly when {@link #status()} is {@link MutationStatus#SUCCESS}
     */
    public boolean success() {
        return status == MutationStatus.SUCCESS;
    }

    private static Optional<BigDecimal> normalize(
            Optional<BigDecimal> value, boolean signed, String name) {
        Objects.requireNonNull(value, name);
        return value.map(amount -> signed
                ? MonetaryAmounts.signed(amount, name)
                : MonetaryAmounts.nonNegative(amount, name));
    }

    private static void requirePresent(Optional<?> value, String name) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required for a successful result");
        }
    }

    private static void validateBreakdown(
            MutationType type,
            BalanceSnapshot after,
            BigDecimal delta,
            BigDecimal baseAmount,
            BigDecimal multiplier,
            BigDecimal finalAmount) {
        switch (type) {
            case AWARD -> {
                BigDecimal multiplied = baseAmount.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
                if (delta.signum() <= 0
                        || finalAmount.compareTo(delta) != 0
                        || finalAmount.compareTo(multiplied) != 0) {
                    throw new IllegalArgumentException("award amounts must match the multiplied positive delta");
                }
            }
            case CREDIT -> requireDirectMutation(delta, baseAmount, multiplier, finalAmount, false);
            case DEBIT -> requireDirectMutation(delta, baseAmount, multiplier, finalAmount, true);
            case SET_BALANCE -> {
                if (baseAmount.compareTo(after.balance()) != 0
                        || finalAmount.compareTo(after.balance()) != 0
                        || multiplier.compareTo(BigDecimal.ONE) != 0) {
                    throw new IllegalArgumentException("set balance amounts must match the resulting balance");
                }
            }
        }
    }

    private static void requireDirectMutation(
            BigDecimal delta,
            BigDecimal baseAmount,
            BigDecimal multiplier,
            BigDecimal finalAmount,
            boolean debit) {
        BigDecimal expected = debit ? delta.abs() : delta;
        boolean invalidSign = debit ? delta.signum() >= 0 : delta.signum() <= 0;
        if (invalidSign
                || baseAmount.compareTo(expected) != 0
                || finalAmount.compareTo(expected) != 0
                || multiplier.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("direct mutation amounts do not match its type");
        }
    }
}
