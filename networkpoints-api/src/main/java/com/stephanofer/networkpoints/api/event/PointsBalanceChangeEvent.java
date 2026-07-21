package com.stephanofer.networkpoints.api.event;

import com.stephanofer.networkpoints.api.amount.MonetaryAmounts;
import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import com.stephanofer.networkpoints.api.result.MutationType;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the Paper main thread after one account balance mutation has committed.
 *
 * <p>This immutable, non-cancellable notification is not emitted before commit and cannot prevent
 * or alter the mutation.</p>
 */
public final class PointsBalanceChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID operationId;
    private final MutationType type;
    private final BalanceSnapshot before;
    private final BalanceSnapshot after;
    private final BigDecimal delta;

    /**
     * Creates a post-commit balance change event.
     *
     * @param operationId the committed operation's idempotency key
     * @param type the committed mutation type
     * @param before the account snapshot before the mutation
     * @param after the account snapshot after the mutation
     * @param delta the signed balance change
     */
    public PointsBalanceChangeEvent(
            UUID operationId,
            MutationType type,
            BalanceSnapshot before,
            BalanceSnapshot after,
            BigDecimal delta) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.type = Objects.requireNonNull(type, "type");
        this.before = Objects.requireNonNull(before, "before");
        this.after = Objects.requireNonNull(after, "after");
        this.delta = MonetaryAmounts.signed(delta, "delta");
        if (!before.playerId().equals(after.playerId())) {
            throw new IllegalArgumentException("before and after must belong to the same player");
        }
        if (after.balance().subtract(before.balance()).compareTo(this.delta) != 0) {
            throw new IllegalArgumentException("delta must match the balance change");
        }
        if (after.revision() <= before.revision()) {
            throw new IllegalArgumentException("after revision must be greater than before revision");
        }
        if ((type == MutationType.AWARD || type == MutationType.CREDIT) && this.delta.signum() <= 0) {
            throw new IllegalArgumentException("award and credit deltas must be positive");
        }
        if (type == MutationType.DEBIT && this.delta.signum() >= 0) {
            throw new IllegalArgumentException("debit delta must be negative");
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
     * Returns the mutation category.
     *
     * @return the committed mutation type
     */
    public MutationType type() {
        return type;
    }

    /**
     * Returns the original account state.
     *
     * @return the account snapshot before the mutation
     */
    public BalanceSnapshot before() {
        return before;
    }

    /**
     * Returns the committed account state.
     *
     * @return the account snapshot after the mutation
     */
    public BalanceSnapshot after() {
        return after;
    }

    /**
     * Returns the effective balance change.
     *
     * @return the signed balance change
     */
    public BigDecimal delta() {
        return delta;
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
