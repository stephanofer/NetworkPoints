package com.stephanofer.networkpoints.payment;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PaymentCooldowns {
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Long> availableAt = new ConcurrentHashMap<>();

    public PaymentCooldowns(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean tryAcquire(UUID playerId, long cooldownMillis) {
        Objects.requireNonNull(playerId, "playerId");
        if (cooldownMillis <= 0) {
            throw new IllegalArgumentException("cooldownMillis must be positive");
        }
        long now = this.clock.millis();
        boolean[] acquired = {false};
        this.availableAt.compute(playerId, (ignored, previous) -> {
            if (previous == null || previous <= now) {
                acquired[0] = true;
                try {
                    return Math.addExact(now, cooldownMillis);
                } catch (ArithmeticException overflow) {
                    return Long.MAX_VALUE;
                }
            }
            return previous;
        });
        return acquired[0];
    }

    public void remove(UUID playerId) {
        this.availableAt.remove(playerId);
    }

    public void clear() {
        this.availableAt.clear();
    }
}
