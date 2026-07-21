package com.stephanofer.networkpoints.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BalanceCacheTest {

    @Test
    void deduplicatesConcurrentLoads() {
        UUID playerId = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();
        CompletableFuture<BalanceSnapshot> pending = new CompletableFuture<>();
        try (BalanceCache cache = cache(ignored -> {
            loads.incrementAndGet();
            return pending;
        })) {
            CompletableFuture<BalanceSnapshot> first = cache.get(playerId);
            CompletableFuture<BalanceSnapshot> second = cache.get(playerId);

            assertSame(first, second);
            assertEquals(1, loads.get());
            pending.complete(snapshot(playerId, "10.00", 1));
            assertEquals(snapshot(playerId, "10.00", 1), first.join());
        }
    }

    @Test
    void oldLoadCannotOverwriteNewerCommittedSnapshot() {
        UUID playerId = UUID.randomUUID();
        CompletableFuture<BalanceSnapshot> pending = new CompletableFuture<>();
        try (BalanceCache cache = cache(ignored -> pending)) {
            cache.get(playerId);
            BalanceSnapshot committed = snapshot(playerId, "20.00", 2);
            cache.publish(committed);

            pending.complete(snapshot(playerId, "10.00", 1));

            assertEquals(committed, cache.getIfPresent(playerId).orElseThrow());
        }
    }

    @Test
    void oldRefreshCannotOverwriteNewerCommittedSnapshot() {
        UUID playerId = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();
        AtomicReference<CompletableFuture<BalanceSnapshot>> next =
                new AtomicReference<>(CompletableFuture.completedFuture(snapshot(playerId, "10.00", 1)));
        try (BalanceCache cache = cache(ignored -> {
            loads.incrementAndGet();
            return next.get();
        })) {
            cache.get(playerId).join();
            CompletableFuture<BalanceSnapshot> staleRefresh = new CompletableFuture<>();
            next.set(staleRefresh);
            cache.refresh(playerId);
            BalanceSnapshot committed = snapshot(playerId, "30.00", 3);
            cache.publish(committed);

            staleRefresh.complete(snapshot(playerId, "20.00", 2));

            assertEquals(2, loads.get());
            assertEquals(committed, cache.getIfPresent(playerId).orElseThrow());
        }
    }

    private static BalanceCache cache(BalanceLoader loader) {
        return new BalanceCache(100, Duration.ofMinutes(1), Duration.ofMinutes(5), loader);
    }

    private static BalanceSnapshot snapshot(UUID playerId, String balance, long revision) {
        return new BalanceSnapshot(playerId, new BigDecimal(balance), revision);
    }
}
