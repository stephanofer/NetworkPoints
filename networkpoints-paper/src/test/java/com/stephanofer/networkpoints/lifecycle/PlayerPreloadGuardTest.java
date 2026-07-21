package com.stephanofer.networkpoints.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PlayerPreloadGuardTest {

    @Test
    void quitPreventsPendingPreloadFromPublishing() {
        PlayerPreloadGuard guard = new PlayerPreloadGuard();
        UUID playerId = UUID.randomUUID();
        long generation = guard.begin(playerId);
        AtomicInteger actions = new AtomicInteger();

        guard.leave(playerId, actions::incrementAndGet);

        assertEquals(1, actions.get());
        assertFalse(guard.runIfCurrent(playerId, generation, actions::incrementAndGet));
        assertEquals(1, actions.get());
    }

    @Test
    void rejoinSupersedesPreviousPreload() {
        PlayerPreloadGuard guard = new PlayerPreloadGuard();
        UUID playerId = UUID.randomUUID();
        long first = guard.begin(playerId);
        long second = guard.begin(playerId);

        assertFalse(guard.isCurrent(playerId, first));
        assertTrue(guard.isCurrent(playerId, second));
        assertFalse(guard.runIfCurrent(playerId, first, () -> { }));
        assertTrue(guard.runIfCurrent(playerId, second, () -> { }));
    }
}
