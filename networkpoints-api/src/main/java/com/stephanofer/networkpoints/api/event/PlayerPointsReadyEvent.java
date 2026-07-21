package com.stephanofer.networkpoints.api.event;

import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import java.util.Objects;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the Paper main thread when a player's points snapshot becomes available.
 *
 * <p>This immutable, non-cancellable notification indicates that synchronous cache reads can now
 * observe the snapshot on this server.</p>
 */
public final class PlayerPointsReadyEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final BalanceSnapshot snapshot;

    /**
     * Creates a points-ready notification.
     *
     * @param snapshot the snapshot that became available
     */
    public PlayerPointsReadyEvent(BalanceSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    /**
     * Returns the available account state.
     *
     * @return the snapshot that became available
     */
    public BalanceSnapshot snapshot() {
        return snapshot;
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
