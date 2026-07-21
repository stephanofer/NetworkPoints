package com.stephanofer.networkpoints.payment;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

final class PaymentNotificationFilter {
    private final Cache<UUID, Boolean> seen = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    boolean shouldDeliver(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId");
        return this.seen.asMap().putIfAbsent(operationId, Boolean.TRUE) == null;
    }

    void close() {
        this.seen.invalidateAll();
    }
}
