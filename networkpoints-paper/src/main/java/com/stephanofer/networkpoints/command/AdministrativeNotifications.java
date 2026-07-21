package com.stephanofer.networkpoints.command;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hera.craftkit.redis.RedisClient;
import com.hera.craftkit.redis.RedisSubscription;
import com.stephanofer.networkpoints.api.NetworkPointsService;
import com.stephanofer.networkpoints.feedback.FeedbackService;
import com.stephanofer.networkpoints.identity.PlayerIdentityService;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class AdministrativeNotifications implements AutoCloseable {
    private final JavaPlugin plugin;
    private final RedisClient redis;
    private final String serverId;
    private final String channel;
    private final NetworkPointsService points;
    private final PlayerIdentityService identities;
    private final FeedbackService feedback;
    private final Consumer<Throwable> failureHandler;
    private final AdministrativeNotificationCodec codec = new AdministrativeNotificationCodec();
    private final Cache<UUID, Boolean> delivered = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();
    private final RedisSubscription subscription;
    private final AtomicBoolean open = new AtomicBoolean(true);

    public AdministrativeNotifications(JavaPlugin plugin, RedisClient redis, String serverId,
                                       NetworkPointsService points, PlayerIdentityService identities,
                                       FeedbackService feedback, Consumer<Throwable> failureHandler) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.redis = Objects.requireNonNull(redis, "redis");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.points = Objects.requireNonNull(points, "points");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.channel = redis.channel("networkpoints", "administrative-mutation");
        this.subscription = redis.subscriber().subscribe(this.channel, message -> receive(message.payload()));
        this.subscription.initialRegistration().exceptionally(failure -> {
            this.failureHandler.accept(failure);
            return null;
        });
    }

    public void publish(AdministrativeNotification notification) {
        Objects.requireNonNull(notification, "notification");
        if (!this.open.get()) {
            return;
        }
        if (markForDelivery(notification.operationId())) {
            deliver(notification);
        }
        if (this.redis.operationalStatus().isOperational()) {
            this.redis.publisher().publish(this.channel, this.codec.encode(notification)).exceptionally(failure -> {
                this.failureHandler.accept(failure);
                return null;
            });
        }
    }

    private void receive(String payload) {
        if (!this.open.get()) {
            return;
        }
        final AdministrativeNotification notification;
        try {
            notification = this.codec.decode(payload);
        } catch (RuntimeException malformed) {
            this.failureHandler.accept(malformed);
            return;
        }
        if (notification.sourceServerId().equals(this.serverId) || !markForDelivery(notification.operationId())) {
            return;
        }
        this.plugin.getServer().getScheduler().runTask(this.plugin, () -> deliver(notification));
    }

    private boolean markForDelivery(UUID operationId) {
        return this.delivered.asMap().putIfAbsent(operationId, Boolean.TRUE) == null;
    }

    private void deliver(AdministrativeNotification notification) {
        if (!this.open.get()) {
            return;
        }
        Player target = this.plugin.getServer().getPlayer(notification.targetId());
        if (target == null) {
            return;
        }
        CompletableFuture<Component> actor = notification.actorId()
                .map(actorId -> {
                    Player online = this.plugin.getServer().getPlayer(actorId);
                    return online == null ? this.identities.offline(actorId)
                            : CompletableFuture.completedFuture(this.identities.online(online));
                })
                .orElseGet(() -> CompletableFuture.completedFuture(Component.text(notification.actorName())));
        actor.whenComplete((rendered, failure) -> this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
            if (!this.open.get()) {
                return;
            }
            Player current = this.plugin.getServer().getPlayer(notification.targetId());
            if (current == null) {
                return;
            }
            Component renderedActor = rendered;
            if (failure != null) {
                this.failureHandler.accept(failure);
                renderedActor = Component.text(notification.actorName());
            }
            this.feedback.send(current, "mutation-" + notification.operation().messageKey() + "-received", Map.of(
                    "actor", renderedActor,
                    "amount", this.points.formatAmount(notification.amount()),
                    "balance", this.points.formatAmount(notification.balance())));
        }));
    }

    @Override
    public void close() {
        this.open.set(false);
        this.subscription.close();
        this.delivered.invalidateAll();
    }
}
