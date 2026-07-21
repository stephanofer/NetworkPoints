package com.stephanofer.networkpoints.synchronization;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

final class InvalidationFilter {
    private final String serverId;
    private final Cache<InvalidationIdentity, Boolean> seen;

    InvalidationFilter(String serverId) {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.seen = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();
    }

    boolean shouldRefresh(BalanceInvalidation invalidation, boolean cached, long currentRevision) {
        Objects.requireNonNull(invalidation, "invalidation");
        if (this.serverId.equals(invalidation.sourceServerId())
                || !cached
                || invalidation.revision() <= currentRevision) {
            return false;
        }
        return this.seen.asMap().putIfAbsent(
                new InvalidationIdentity(invalidation.operationId(), invalidation.playerId()), Boolean.TRUE) == null;
    }

    void allowRetry(BalanceInvalidation invalidation) {
        Objects.requireNonNull(invalidation, "invalidation");
        this.seen.invalidate(new InvalidationIdentity(invalidation.operationId(), invalidation.playerId()));
    }

    void close() {
        this.seen.invalidateAll();
    }

    private record InvalidationIdentity(UUID operationId, UUID playerId) {
    }
}
