package com.stephanofer.networkpoints.api.event;

import com.stephanofer.networkpoints.api.amount.MonetaryAmounts;
import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the Paper main thread after a booster-eligible award has committed.
 *
 * <p>This immutable, non-cancellable notification is not emitted before commit and cannot prevent
 * or alter the award.</p>
 */
public final class PointsAwardEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID operationId;
    private final BalanceSnapshot before;
    private final BalanceSnapshot after;
    private final BigDecimal baseAmount;
    private final BigDecimal multiplier;
    private final BigDecimal finalAmount;

    /**
     * Creates a post-commit award event.
     *
     * @param operationId the committed operation's idempotency key
     * @param before the recipient snapshot before the award
     * @param after the recipient snapshot after the award
     * @param baseAmount the positive amount before booster multiplication
     * @param multiplier the positive multiplier applied to the award
     * @param finalAmount the positive rounded amount credited
     */
    public PointsAwardEvent(
            UUID operationId,
            BalanceSnapshot before,
            BalanceSnapshot after,
            BigDecimal baseAmount,
            BigDecimal multiplier,
            BigDecimal finalAmount) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.before = Objects.requireNonNull(before, "before");
        this.after = Objects.requireNonNull(after, "after");
        this.baseAmount = MonetaryAmounts.positive(baseAmount, "baseAmount");
        this.multiplier = MonetaryAmounts.multiplier(multiplier, "multiplier");
        this.finalAmount = MonetaryAmounts.positive(finalAmount, "finalAmount");
        if (!before.playerId().equals(after.playerId())) {
            throw new IllegalArgumentException("before and after must belong to the same player");
        }
        if (after.balance().subtract(before.balance()).compareTo(this.finalAmount) != 0) {
            throw new IllegalArgumentException("finalAmount must match the balance change");
        }
        if (after.revision() <= before.revision()) {
            throw new IllegalArgumentException("after revision must be greater than before revision");
        }
        if (baseAmount.multiply(multiplier).setScale(2, RoundingMode.HALF_UP).compareTo(finalAmount) != 0) {
            throw new IllegalArgumentException("finalAmount must equal the rounded multiplied baseAmount");
        }
    }

    /**
     * Returns the operation identity.
     *
     * @return the committed operation's idempotency key
     */
    public UUID operationId() {
        return operationId;
    }

    /**
     * Returns the original account state.
     *
     * @return the recipient snapshot before the award
     */
    public BalanceSnapshot before() {
        return before;
    }

    /**
     * Returns the committed account state.
     *
     * @return the recipient snapshot after the award
     */
    public BalanceSnapshot after() {
        return after;
    }

    /**
     * Returns the award's base amount.
     *
     * @return the amount before booster multiplication
     */
    public BigDecimal baseAmount() {
        return baseAmount;
    }

    /**
     * Returns the applied multiplier.
     *
     * @return the multiplier applied to the award
     */
    public BigDecimal multiplier() {
        return multiplier;
    }

    /**
     * Returns the effective award amount.
     *
     * @return the rounded amount credited
     */
    public BigDecimal finalAmount() {
        return finalAmount;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Returns this event type's handlers.
     *
     * @return the shared Bukkit handler list for this event type
     */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
