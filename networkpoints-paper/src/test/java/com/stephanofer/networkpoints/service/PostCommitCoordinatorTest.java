package com.stephanofer.networkpoints.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stephanofer.networkpoints.account.BalanceCache;
import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import com.stephanofer.networkpoints.api.result.MutationResult;
import com.stephanofer.networkpoints.api.result.MutationStatus;
import com.stephanofer.networkpoints.api.result.MutationType;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostCommitCoordinatorTest {

    @Test
    void updatesCacheBeforeRedisAndDispatchesEventsAfterPublication() {
        UUID playerId = UUID.randomUUID();
        BalanceSnapshot before = snapshot(playerId, "10.00", 1);
        BalanceSnapshot after = snapshot(playerId, "12.00", 2);
        List<String> order = new ArrayList<>();
        try (BalanceCache cache = cache()) {
            PostCommitCoordinator coordinator = new PostCommitCoordinator(
                    cache,
                    (operationId, kind, published) -> {
                        assertEquals(after, cache.getIfPresent(playerId).orElseThrow());
                        order.add("redis");
                    },
                    event -> order.add(event.getEventName()));

            coordinator.afterMutation(result(before, after, false));

            assertEquals(List.of("redis", "PointsBalanceChangeEvent"), order);
        }
    }

    @Test
    void replayRepairsLocalCacheWithoutRepeatingExternalEffects() {
        UUID playerId = UUID.randomUUID();
        BalanceSnapshot before = snapshot(playerId, "10.00", 1);
        BalanceSnapshot after = snapshot(playerId, "12.00", 2);
        List<String> effects = new ArrayList<>();
        try (BalanceCache cache = cache()) {
            PostCommitCoordinator coordinator = new PostCommitCoordinator(
                    cache,
                    (operationId, kind, published) -> effects.add("redis"),
                    event -> effects.add("event"));

            coordinator.afterMutation(result(before, after, true));

            assertEquals(after, cache.getIfPresent(playerId).orElseThrow());
            assertTrue(effects.isEmpty());
        }
    }

    private static MutationResult result(BalanceSnapshot before, BalanceSnapshot after, boolean replayed) {
        BigDecimal amount = new BigDecimal("2.00");
        return new MutationResult(
                MutationStatus.SUCCESS, MutationType.CREDIT, UUID.randomUUID(), before.playerId(),
                Optional.of(before), Optional.of(after), Optional.of(amount), Optional.of(amount),
                Optional.of(BigDecimal.ONE), Optional.of(amount), replayed);
    }

    private static BalanceCache cache() {
        return new BalanceCache(100, Duration.ofMinutes(1), Duration.ofMinutes(5),
                ignored -> java.util.concurrent.CompletableFuture.failedFuture(new AssertionError("unexpected load")));
    }

    private static BalanceSnapshot snapshot(UUID playerId, String balance, long revision) {
        return new BalanceSnapshot(playerId, new BigDecimal(balance), revision);
    }
}
