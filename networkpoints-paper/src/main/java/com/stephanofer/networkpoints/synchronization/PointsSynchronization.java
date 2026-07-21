package com.stephanofer.networkpoints.synchronization;

import com.hera.craftkit.redis.RedisClient;
import com.hera.craftkit.redis.RedisSubscription;
import com.stephanofer.networkpoints.account.BalanceCache;
import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import com.stephanofer.networkpoints.persistence.TransactionKind;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public final class PointsSynchronization implements PointsInvalidationPublisher, AutoCloseable {

    private final RedisClient redis;
    private final String serverId;
    private final String channel;
    private final BalanceInvalidationCodec codec;
    private final BalanceCache balances;
    private final Consumer<Throwable> failureHandler;
    private final InvalidationFilter filter;
    private final RedisSubscription subscription;

    public PointsSynchronization(
            RedisClient redis,
            String serverId,
            BalanceInvalidationCodec codec,
            BalanceCache balances,
            Consumer<Throwable> failureHandler) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.balances = Objects.requireNonNull(balances, "balances");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.filter = new InvalidationFilter(serverId);
        this.channel = redis.channel("networkpoints", "balance-invalidated");
        this.subscription = redis.subscriber().subscribe(this.channel, message -> receive(message.payload()));
        this.subscription.initialRegistration().exceptionally(failure -> {
            this.failureHandler.accept(failure);
            return null;
        });
    }

    @Override
    public void publish(UUID operationId, TransactionKind kind, BalanceSnapshot snapshot) {
        if (!this.redis.operationalStatus().isOperational()) {
            return;
        }
        BalanceInvalidation invalidation = new BalanceInvalidation(
                operationId, this.serverId, snapshot.playerId(), snapshot.revision(), kind);
        this.redis.publisher().publish(this.channel, this.codec.encode(invalidation)).exceptionally(failure -> {
            this.failureHandler.accept(failure);
            return null;
        });
    }

    void receive(String payload) {
        final BalanceInvalidation invalidation;
        try {
            invalidation = this.codec.decode(payload);
        } catch (RuntimeException malformed) {
            this.failureHandler.accept(malformed);
            return;
        }
        boolean cached = this.balances.contains(invalidation.playerId());
        long currentRevision = this.balances.getIfPresent(invalidation.playerId())
                .map(BalanceSnapshot::revision)
                .orElse(-1L);
        if (!this.filter.shouldRefresh(invalidation, cached, currentRevision)) {
            return;
        }
        this.balances.refresh(invalidation.playerId()).exceptionally(failure -> {
            this.failureHandler.accept(failure);
            return null;
        });
    }

    @Override
    public void close() {
        this.subscription.close();
        this.filter.close();
    }
}
