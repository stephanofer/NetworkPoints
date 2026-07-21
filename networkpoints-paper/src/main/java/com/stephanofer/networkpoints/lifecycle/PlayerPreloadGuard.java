package com.stephanofer.networkpoints.lifecycle;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class PlayerPreloadGuard {
    private final ConcurrentHashMap<UUID, Long> generations = new ConcurrentHashMap<>();

    long begin(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return this.generations.merge(playerId, 1L, Long::sum);
    }

    boolean runIfCurrent(UUID playerId, long generation, Runnable action) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(action, "action");
        boolean[] ran = {false};
        this.generations.computeIfPresent(playerId, (ignored, current) -> {
            if (current == generation) {
                action.run();
                ran[0] = true;
            }
            return current;
        });
        return ran[0];
    }

    void leave(UUID playerId, Runnable action) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(action, "action");
        this.generations.compute(playerId, (ignored, current) -> {
            action.run();
            return current == null ? 1L : current + 1L;
        });
    }

    boolean isCurrent(UUID playerId, long generation) {
        return this.generations.getOrDefault(Objects.requireNonNull(playerId, "playerId"), 0L) == generation;
    }

    void clear() {
        this.generations.clear();
    }
}
