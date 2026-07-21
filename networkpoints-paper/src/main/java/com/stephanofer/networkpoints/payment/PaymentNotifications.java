package com.stephanofer.networkpoints.payment;

import com.hera.craftkit.redis.RedisClient;
import com.hera.craftkit.redis.RedisSubscription;
import com.stephanofer.networkpoints.api.NetworkPointsService;
import com.stephanofer.networkpoints.feedback.FeedbackService;
import com.stephanofer.networkpoints.identity.PlayerIdentityService;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaymentNotifications implements AutoCloseable {
    private final JavaPlugin plugin;
    private final RedisClient redis;
    private final String serverId;
    private final String channel;
    private final PaymentNotificationCodec codec;
    private final NetworkPointsService points;
    private final PlayerIdentityService identities;
    private final FeedbackService feedback;
    private final Consumer<Throwable> failureHandler;
    private final RedisSubscription subscription;
    private final AtomicBoolean open = new AtomicBoolean(true);

    public PaymentNotifications(JavaPlugin plugin, RedisClient redis, String serverId,
                                NetworkPointsService points, PlayerIdentityService identities,
                                FeedbackService feedback, Consumer<Throwable> failureHandler) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.redis = Objects.requireNonNull(redis, "redis");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.points = Objects.requireNonNull(points, "points");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.codec = new PaymentNotificationCodec();
        this.channel = redis.channel("networkpoints", "payment-received");
        this.subscription = redis.subscriber().subscribe(this.channel, message -> receive(message.payload()));
        this.subscription.initialRegistration().exceptionally(failure -> {
            this.failureHandler.accept(failure);
            return null;
        });
    }

    public void publish(PaymentNotification notification) {
        Objects.requireNonNull(notification, "notification");
        if (!this.open.get()) {
            return;
        }
        deliver(notification);
        if (!this.redis.operationalStatus().isOperational()) {
            return;
        }
        this.redis.publisher().publish(this.channel, this.codec.encode(notification)).exceptionally(failure -> {
            this.failureHandler.accept(failure);
            return null;
        });
    }

    private void receive(String payload) {
        if (!this.open.get()) {
            return;
        }
        final PaymentNotification notification;
        try {
            notification = this.codec.decode(payload);
        } catch (RuntimeException malformed) {
            this.failureHandler.accept(malformed);
            return;
        }
        if (notification.sourceServerId().equals(this.serverId)) {
            return;
        }
        this.plugin.getServer().getScheduler().runTask(this.plugin, () -> deliver(notification));
    }

    private void deliver(PaymentNotification notification) {
        if (!this.open.get()) {
            return;
        }
        Player recipient = this.plugin.getServer().getPlayer(notification.recipientId());
        if (recipient == null) {
            return;
        }
        Player sender = this.plugin.getServer().getPlayer(notification.senderId());
        java.util.concurrent.CompletableFuture<Component> identity = sender == null
                ? this.identities.offline(notification.senderId())
                : java.util.concurrent.CompletableFuture.completedFuture(this.identities.online(sender));
        identity.whenComplete((rendered, failure) -> this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
            if (!this.open.get()) {
                return;
            }
            Player current = this.plugin.getServer().getPlayer(notification.recipientId());
            if (current == null) {
                return;
            }
            if (failure != null) {
                this.failureHandler.accept(failure);
                return;
            }
            this.feedback.send(current, "pay-received", Map.of(
                    "amount", this.points.formatAmount(notification.amount()), "sender", rendered));
        }));
    }

    @Override
    public void close() {
        this.open.set(false);
        this.subscription.close();
    }
}
