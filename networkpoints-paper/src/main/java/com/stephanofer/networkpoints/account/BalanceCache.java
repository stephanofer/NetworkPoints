package com.stephanofer.networkpoints.account;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class BalanceCache implements AutoCloseable {

    private final AsyncLoadingCache<UUID, BalanceSnapshot> cache;

    public BalanceCache(long maximumSize, Duration refreshAfterWrite, Duration expireAfterAccess, BalanceLoader loader) {
        Objects.requireNonNull(loader, "loader");
        this.cache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .refreshAfterWrite(Objects.requireNonNull(refreshAfterWrite, "refreshAfterWrite"))
                .expireAfterAccess(Objects.requireNonNull(expireAfterAccess, "expireAfterAccess"))
                .buildAsync((playerId, executor) -> loader.load(playerId));
    }

    public Optional<BalanceSnapshot> getIfPresent(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        CompletableFuture<BalanceSnapshot> loaded = this.cache.getIfPresent(playerId);
        if (loaded == null || !loaded.isDone() || loaded.isCompletedExceptionally() || loaded.isCancelled()) {
            return Optional.empty();
        }
        return Optional.ofNullable(loaded.getNow(null));
    }

    public boolean contains(UUID playerId) {
        return this.cache.getIfPresent(Objects.requireNonNull(playerId, "playerId")) != null;
    }

    public CompletableFuture<BalanceSnapshot> get(UUID playerId) {
        return this.cache.get(Objects.requireNonNull(playerId, "playerId"));
    }

    public CompletableFuture<BalanceSnapshot> refresh(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return this.cache.synchronous().refresh(playerId);
    }

    public BalanceSnapshot publish(BalanceSnapshot candidate) {
        Objects.requireNonNull(candidate, "candidate");
        CompletableFuture<BalanceSnapshot> selected = this.cache.asMap().compute(candidate.playerId(), (playerId, current) -> {
            BalanceSnapshot existing = completedValue(current);
            return existing != null && existing.revision() > candidate.revision()
                    ? current
                    : CompletableFuture.completedFuture(candidate);
        });
        return Objects.requireNonNull(selected).getNow(candidate);
    }

    public void invalidate(UUID playerId) {
        this.cache.synchronous().invalidate(Objects.requireNonNull(playerId, "playerId"));
    }

    @Override
    public void close() {
        this.cache.synchronous().invalidateAll();
        this.cache.synchronous().cleanUp();
    }

    private static BalanceSnapshot completedValue(CompletableFuture<BalanceSnapshot> future) {
        if (future == null || !future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) {
            return null;
        }
        return future.getNow(null);
    }
}
