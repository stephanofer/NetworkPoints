package com.stephanofer.networkpoints.payment;

import com.stephanofer.networkpoints.account.AccountRecord;
import com.stephanofer.networkpoints.account.AccountStore;
import com.stephanofer.networkpoints.api.NetworkPointsService;
import com.stephanofer.networkpoints.api.amount.AmountParseResult;
import com.stephanofer.networkpoints.api.request.TransferRequest;
import com.stephanofer.networkpoints.api.result.TransferResult;
import com.stephanofer.networkpoints.api.source.MutationContext;
import com.stephanofer.networkpoints.config.ConfigSnapshot;
import com.stephanofer.networkpoints.feedback.FeedbackService;
import com.stephanofer.networkpoints.identity.PlayerIdentityService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaymentController implements AutoCloseable {
    private static final Key PAYMENT_SOURCE = Key.key("networkpoints:player_payment");

    private final JavaPlugin plugin;
    private final NetworkPointsService points;
    private final AccountStore accounts;
    private final PlayerIdentityService identities;
    private final FeedbackService feedback;
    private final Supplier<ConfigSnapshot> configuration;
    private final PaymentNotifications notifications;
    private final PaymentPolicy policy = new PaymentPolicy();
    private final PaymentSessionRegistry sessions = new PaymentSessionRegistry(Clock.systemUTC());
    private final PaymentCooldowns cooldowns = new PaymentCooldowns(Clock.systemUTC());
    private final PaymentDialogFactory dialogs;
    private final AtomicBoolean open = new AtomicBoolean(true);

    public PaymentController(JavaPlugin plugin, NetworkPointsService points, AccountStore accounts,
                             PlayerIdentityService identities, FeedbackService feedback,
                             Supplier<ConfigSnapshot> configuration, PaymentNotifications notifications) {
        this.plugin = plugin;
        this.points = points;
        this.accounts = accounts;
        this.identities = identities;
        this.feedback = feedback;
        this.configuration = configuration;
        this.notifications = notifications;
        this.dialogs = new PaymentDialogFactory(points, feedback);
    }

    public void start(Player sender, String recipientName, String input) {
        if (!this.open.get()) {
            this.feedback.send(sender, "service-unavailable", Map.of());
            return;
        }
        if (!this.feedback.isReady(sender)) {
            this.feedback.send(sender, "pay-settings-not-ready", Map.of());
            return;
        }
        ConfigSnapshot.Payments config = this.configuration.get().reloadable().payments();
        if (!config.enabled()) {
            this.feedback.send(sender, "pay-disabled", Map.of());
            return;
        }
        AmountParseResult parsed = this.points.parseAmount(input);
        if (!(parsed instanceof AmountParseResult.Success success) || success.amount().signum() <= 0) {
            this.feedback.send(sender, "invalid-amount", Map.of());
            return;
        }
        resolve(recipientName).whenComplete((account, failure) -> main(() -> {
            if (!sender.isOnline() || !this.open.get()) {
                return;
            }
            if (failure != null) {
                this.feedback.send(sender, "service-unavailable", Map.of());
            } else if (account.isEmpty()) {
                this.feedback.send(sender, "unknown-player", Map.of());
            } else {
                prepare(sender, account.orElseThrow(), success.amount());
            }
        }));
    }

    private void prepare(Player sender, AccountRecord recipient, BigDecimal amount) {
        if (!this.open.get()) {
            return;
        }
        ConfigSnapshot.Reloadable reloadable = this.configuration.get().reloadable();
        boolean recipientOnline = this.plugin.getServer().getPlayer(recipient.playerId()) != null;
        PaymentPolicy.Decision decision = this.policy.evaluate(
                reloadable.payments(), sender.getUniqueId(), recipient.playerId(), amount, recipientOnline);
        if (!decision.accepted()) {
            reject(sender, decision.status(), reloadable.payments());
            return;
        }
        if (!this.cooldowns.tryAcquire(sender.getUniqueId(), reloadable.payments().cooldownMillis())) {
            this.feedback.send(sender, "pay-cooldown", Map.of());
            return;
        }
        if (!decision.requiresConfirmation()) {
            execute(sender, recipient, amount, UUID.randomUUID());
            return;
        }
        showConfirmation(sender, recipient, amount, reloadable);
    }

    private void showConfirmation(Player sender, AccountRecord recipient, BigDecimal amount,
                                  ConfigSnapshot.Reloadable reloadable) {
        ConfigSnapshot.Confirmation confirmation = reloadable.payments().confirmation();
        PaymentSession session = this.sessions.open(sender.getUniqueId(), recipient.playerId(), amount,
                Duration.ofSeconds(confirmation.expiresAfterSeconds()));
        long seconds = confirmation.expiresAfterSeconds();
        long ticks = seconds > Long.MAX_VALUE / 20L ? Long.MAX_VALUE : Math.max(1L, seconds * 20L);
        this.plugin.getServer().getScheduler().runTaskLater(
                this.plugin, () -> this.sessions.remove(session.senderId(), session.token()), ticks);
        Player onlineRecipient = this.plugin.getServer().getPlayer(recipient.playerId());
        CompletableFuture<Component> recipientIdentity = onlineRecipient == null
                ? this.identities.offline(recipient.playerId())
                : CompletableFuture.completedFuture(this.identities.online(onlineRecipient));
        Component senderIdentity = this.identities.online(sender);
        recipientIdentity.whenComplete((renderedRecipient, failure) -> main(() -> {
            if (!sender.isOnline() || !this.open.get() || !this.feedback.isReady(sender)
                    || !this.sessions.isActive(session.senderId(), session.token())) {
                return;
            }
            if (failure != null) {
                this.sessions.remove(session.senderId(), session.token());
                this.feedback.send(sender, "service-unavailable", Map.of());
                return;
            }
            sender.showDialog(this.dialogs.create(sender, session, senderIdentity, renderedRecipient, reloadable,
                    clicked -> main(() -> confirm(clicked, session)),
                    clicked -> main(() -> cancel(clicked, session))));
        }));
    }

    private void confirm(Player sender, PaymentSession requested) {
        if (!this.open.get() || !sender.getUniqueId().equals(requested.senderId())) {
            return;
        }
        PaymentSessionRegistry.Claim claim = this.sessions.claim(sender.getUniqueId(), requested.token());
        if (claim.status() != PaymentSessionRegistry.Status.CLAIMED) {
            this.feedback.send(sender, "pay-confirmation-expired", Map.of());
            return;
        }
        PaymentSession session = claim.session().orElseThrow();
        String permission = this.configuration.get().restartRequired().commands().entries().get("pay").permission();
        if (!sender.hasPermission(permission)) {
            this.feedback.send(sender, "no-permission", Map.of());
            return;
        }
        this.accounts.find(session.recipientId()).whenComplete((account, failure) -> main(() -> {
            if (!sender.isOnline()) {
                return;
            }
            if (failure != null) {
                this.feedback.send(sender, "service-unavailable", Map.of());
                return;
            }
            if (account.isEmpty()) {
                this.feedback.send(sender, "pay-account-not-found", Map.of());
                return;
            }
            ConfigSnapshot.Payments config = this.configuration.get().reloadable().payments();
            PaymentPolicy.Decision decision = this.policy.evaluate(config, session.senderId(), session.recipientId(),
                    session.amount(), this.plugin.getServer().getPlayer(session.recipientId()) != null);
            if (!decision.accepted()) {
                reject(sender, decision.status(), config);
                return;
            }
            execute(sender, account.orElseThrow(), session.amount(), session.operationId());
        }));
    }

    private void cancel(Player sender, PaymentSession session) {
        if (sender.getUniqueId().equals(session.senderId())
                && this.sessions.remove(session.senderId(), session.token())) {
            this.feedback.send(sender, "pay-cancelled", Map.of());
        }
    }

    private void execute(Player sender, AccountRecord recipient, BigDecimal amount, UUID operationId) {
        if (!this.open.get()) {
            return;
        }
        MutationContext context = new MutationContext(operationId, PAYMENT_SOURCE,
                Optional.of(sender.getUniqueId()), Optional.of(recipient.lastKnownName()));
        this.points.transfer(new TransferRequest(sender.getUniqueId(), recipient.playerId(), amount, context))
                .whenComplete((result, failure) -> main(() -> result(sender, recipient, result, failure)));
    }

    private void result(Player sender, AccountRecord recipient, TransferResult result, Throwable failure) {
        if (!this.open.get()) {
            return;
        }
        if (failure != null) {
            if (sender.isOnline()) {
                this.feedback.send(sender, "service-unavailable", Map.of());
            }
            return;
        }
        if (!result.success()) {
            if (sender.isOnline()) {
                this.feedback.send(sender, "pay-" + result.status().name().toLowerCase().replace('_', '-'), Map.of());
            }
            return;
        }
        if (!result.replayed()) {
            this.notifications.publish(new PaymentNotification(result.operationId(),
                    this.configuration.get().restartRequired().serverId(), result.senderId(),
                    result.recipientId(), result.amount().orElseThrow()));
        }
        if (!sender.isOnline()) {
            return;
        }
        Player onlineRecipient = this.plugin.getServer().getPlayer(recipient.playerId());
        CompletableFuture<Component> identity = onlineRecipient == null
                ? this.identities.offline(recipient.playerId())
                : CompletableFuture.completedFuture(this.identities.online(onlineRecipient));
        identity.whenComplete((rendered, identityFailure) -> main(() -> {
            if (!sender.isOnline()) {
                return;
            }
            if (identityFailure != null) {
                this.feedback.send(sender, "service-unavailable", Map.of());
                return;
            }
            this.feedback.send(sender, "pay-sent", Map.of(
                    "amount", this.points.formatAmount(result.amount().orElseThrow()),
                    "recipient", rendered,
                    "balance", this.points.formatAmount(result.senderAfter().orElseThrow().balance())));
        }));
    }

    private void reject(Player sender, PaymentPolicy.Status status, ConfigSnapshot.Payments config) {
        switch (status) {
            case DISABLED -> this.feedback.send(sender, "pay-disabled", Map.of());
            case SELF_PAYMENT -> this.feedback.send(sender, "pay-self", Map.of());
            case OFFLINE_RECIPIENT -> this.feedback.send(sender, "pay-offline-recipient", Map.of());
            case BELOW_MINIMUM -> this.feedback.send(sender, "pay-below-minimum",
                    Map.of("amount", this.points.formatAmount(config.minimumAmount())));
            case ABOVE_MAXIMUM -> this.feedback.send(sender, "pay-above-maximum",
                    Map.of("amount", this.points.formatAmount(config.maximumAmount())));
            case ACCEPTED -> throw new IllegalArgumentException("Accepted payment cannot be rejected");
        }
    }

    private CompletableFuture<Optional<AccountRecord>> resolve(String name) {
        Player online = this.plugin.getServer().getPlayerExact(name);
        return online == null ? this.accounts.findByName(name) : this.accounts.find(online.getUniqueId());
    }

    public void clear(UUID playerId) {
        this.sessions.remove(playerId);
        this.cooldowns.remove(playerId);
    }

    public void clearSessions() {
        Set<UUID> players = this.sessions.clear();
        players.forEach(playerId -> {
            Player player = this.plugin.getServer().getPlayer(playerId);
            if (player != null) {
                player.closeDialog();
            }
        });
    }

    @Override
    public void close() {
        this.open.set(false);
        clearSessions();
        this.cooldowns.clear();
    }

    private void main(Runnable action) {
        this.plugin.getServer().getScheduler().runTask(this.plugin, action);
    }
}
