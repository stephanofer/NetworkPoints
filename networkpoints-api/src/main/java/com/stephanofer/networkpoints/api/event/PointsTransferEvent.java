package com.stephanofer.networkpoints.api.event;

import com.stephanofer.networkpoints.api.amount.MonetaryAmounts;
import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the Paper main thread after an atomic points transfer has committed.
 *
 * <p>This immutable, non-cancellable notification is not emitted before commit and cannot prevent
 * or alter the transfer.</p>
 */
public final class PointsTransferEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID operationId;
    private final BalanceSnapshot senderBefore;
    private final BalanceSnapshot senderAfter;
    private final BalanceSnapshot recipientBefore;
    private final BalanceSnapshot recipientAfter;
    private final BigDecimal amount;

    /**
     * Creates a post-commit transfer event.
     *
     * @param operationId the committed operation's idempotency key
     * @param senderBefore the sender snapshot before the transfer
     * @param senderAfter the sender snapshot after the transfer
     * @param recipientBefore the recipient snapshot before the transfer
     * @param recipientAfter the recipient snapshot after the transfer
     * @param amount the positive amount transferred
     */
    public PointsTransferEvent(
            UUID operationId,
            BalanceSnapshot senderBefore,
            BalanceSnapshot senderAfter,
            BalanceSnapshot recipientBefore,
            BalanceSnapshot recipientAfter,
            BigDecimal amount) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.senderBefore = Objects.requireNonNull(senderBefore, "senderBefore");
        this.senderAfter = Objects.requireNonNull(senderAfter, "senderAfter");
        this.recipientBefore = Objects.requireNonNull(recipientBefore, "recipientBefore");
        this.recipientAfter = Objects.requireNonNull(recipientAfter, "recipientAfter");
        this.amount = MonetaryAmounts.positive(amount, "amount");
        validateSnapshots();
    }

    private void validateSnapshots() {
        if (!senderBefore.playerId().equals(senderAfter.playerId())) {
            throw new IllegalArgumentException("sender snapshots must belong to the same player");
        }
        if (!recipientBefore.playerId().equals(recipientAfter.playerId())) {
            throw new IllegalArgumentException("recipient snapshots must belong to the same player");
        }
        if (senderBefore.playerId().equals(recipientBefore.playerId())) {
            throw new IllegalArgumentException("sender and recipient must be different");
        }
        if (senderAfter.revision() <= senderBefore.revision()
                || recipientAfter.revision() <= recipientBefore.revision()) {
            throw new IllegalArgumentException("after revisions must be greater than before revisions");
        }
        if (senderBefore.balance().subtract(senderAfter.balance()).compareTo(amount) != 0
                || recipientAfter.balance().subtract(recipientBefore.balance()).compareTo(amount) != 0) {
            throw new IllegalArgumentException("snapshot changes must match the transferred amount");
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
     * Returns the sender's original state.
     *
     * @return the sender snapshot before the transfer
     */
    public BalanceSnapshot senderBefore() {
        return senderBefore;
    }

    /**
     * Returns the sender's committed state.
     *
     * @return the sender snapshot after the transfer
     */
    public BalanceSnapshot senderAfter() {
        return senderAfter;
    }

    /**
     * Returns the recipient's original state.
     *
     * @return the recipient snapshot before the transfer
     */
    public BalanceSnapshot recipientBefore() {
        return recipientBefore;
    }

    /**
     * Returns the recipient's committed state.
     *
     * @return the recipient snapshot after the transfer
     */
    public BalanceSnapshot recipientAfter() {
        return recipientAfter;
    }

    /**
     * Returns the effective transfer amount.
     *
     * @return the positive amount transferred
     */
    public BigDecimal amount() {
        return amount;
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
