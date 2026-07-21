package com.stephanofer.networkpoints.command;

import com.stephanofer.networkpoints.account.AccountRecord;
import com.stephanofer.networkpoints.account.AccountStore;
import com.stephanofer.networkpoints.api.NetworkPointsService;
import com.stephanofer.networkpoints.api.amount.AmountParseResult;
import com.stephanofer.networkpoints.api.request.CreditRequest;
import com.stephanofer.networkpoints.api.request.DebitRequest;
import com.stephanofer.networkpoints.api.request.SetBalanceRequest;
import com.stephanofer.networkpoints.api.result.MutationResult;
import com.stephanofer.networkpoints.api.source.MutationContext;
import com.stephanofer.networkpoints.config.ConfigSnapshot;
import com.stephanofer.networkpoints.feedback.FeedbackService;
import com.stephanofer.networkpoints.identity.PlayerIdentityService;
import com.stephanofer.networkpoints.lifecycle.LifecycleState;
import com.stephanofer.networkpoints.persistence.AuditStore;
import com.stephanofer.networkpoints.persistence.TransactionRecord;
import com.stephanofer.networkpoints.payment.PaymentController;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.Command;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.exception.ArgumentParseException;
import org.incendo.cloud.exception.InvalidSyntaxException;
import org.incendo.cloud.exception.NoPermissionException;
import org.incendo.cloud.paper.PaperCommandManager;
import org.incendo.cloud.suggestion.SuggestionProvider;

import static org.incendo.cloud.parser.standard.IntegerParser.integerParser;
import static org.incendo.cloud.parser.standard.StringParser.stringParser;

public final class PointsCommandController {
    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final JavaPlugin plugin;
    private final PaperCommandManager.Bootstrapped<CommandSourceStack> manager;
    private final NetworkPointsService points;
    private final AccountStore accounts;
    private final AuditStore audit;
    private final PlayerIdentityService identities;
    private final FeedbackService feedback;
    private final Supplier<ConfigSnapshot> configuration;
    private final Supplier<LifecycleState> state;
    private final Supplier<String> redisState;
    private final ReloadAction reload;
    private final PaymentController payments;

    public PointsCommandController(JavaPlugin plugin, PaperCommandManager.Bootstrapped<CommandSourceStack> manager,
                                   NetworkPointsService points, AccountStore accounts, AuditStore audit,
                                   PlayerIdentityService identities, FeedbackService feedback,
                                    Supplier<ConfigSnapshot> configuration, Supplier<LifecycleState> state,
                                    Supplier<String> redisState, ReloadAction reload, PaymentController payments) {
        this.plugin = plugin;
        this.manager = manager;
        this.points = points;
        this.accounts = accounts;
        this.audit = audit;
        this.identities = identities;
        this.feedback = feedback;
        this.configuration = configuration;
        this.state = state;
        this.redisState = redisState;
        this.reload = reload;
        this.payments = payments;
    }

    public void register() {
        ConfigSnapshot.Commands commands = this.configuration.get().restartRequired().commands();
        ConfigSnapshot.Command root = commands.root();
        Command.Builder<CommandSourceStack> base = this.manager.commandBuilder(
                root.name(), root.aliases().toArray(String[]::new)).permission(root.permission())
                .commandDescription(Description.of(root.description()));
        this.manager.command(base.handler(context -> ownBalance(sender(context.sender()))));
        registerBalance(base, commands.entries().get("balance"));
        registerPay(base, commands.entries().get("pay"));
        registerMutation(base, commands.entries().get("give"), Mutation.CREDIT);
        registerMutation(base, commands.entries().get("take"), Mutation.DEBIT);
        registerMutation(base, commands.entries().get("set"), Mutation.SET);
        registerReset(base, commands.entries().get("reset"));
        registerHistory(base, commands.entries().get("history"));
        registerReload(base, commands.entries().get("reload"));
        registerStatus(base, commands.entries().get("status"));
        registerExceptions();
    }

    private void registerPay(Command.Builder<CommandSourceStack> base, ConfigSnapshot.Command command) {
        if (!command.enabled()) {
            return;
        }
        this.manager.command(base.literal(command.name(), command.aliases().toArray(String[]::new))
                .permission(command.permission())
                .commandDescription(Description.of(command.description()))
                .required("player", stringParser(), paymentSuggestions())
                .required("amount", stringParser())
                .handler(context -> {
                    CommandSender sender = sender(context.sender());
                    if (!(sender instanceof Player player)) {
                        this.feedback.send(sender, "player-only", Map.of());
                        return;
                    }
                    this.payments.start(player, context.get("player"), context.get("amount"));
                }));
    }

    private void registerBalance(Command.Builder<CommandSourceStack> base, ConfigSnapshot.Command command) {
        if (!command.enabled()) {
            return;
        }
        this.manager.command(base.literal(command.name(), command.aliases().toArray(String[]::new))
                .permission(command.permission())
                .commandDescription(Description.of(command.description()))
                .handler(context -> ownBalance(sender(context.sender()))));
        this.manager.command(base.literal(command.name(), command.aliases().toArray(String[]::new))
                .permission(command.permission())
                .commandDescription(Description.of(command.description()))
                .required("player", stringParser(), playerSuggestions())
                .handler(context -> resolveAndShowBalance(sender(context.sender()), context.get("player"))));
    }

    private void registerMutation(Command.Builder<CommandSourceStack> base, ConfigSnapshot.Command command,
                                  Mutation mutation) {
        if (!command.enabled()) {
            return;
        }
        this.manager.command(base.literal(command.name(), command.aliases().toArray(String[]::new))
                .permission(command.permission())
                .commandDescription(Description.of(command.description()))
                .required("player", stringParser(), playerSuggestions())
                .required("amount", stringParser())
                .handler(context -> mutate(sender(context.sender()), context.get("player"), context.get("amount"), mutation)));
    }

    private void registerReset(Command.Builder<CommandSourceStack> base, ConfigSnapshot.Command command) {
        if (!command.enabled()) {
            return;
        }
        this.manager.command(base.literal(command.name(), command.aliases().toArray(String[]::new))
                .permission(command.permission()).commandDescription(Description.of(command.description()))
                .required("player", stringParser(), playerSuggestions())
                .handler(context -> mutateResolved(sender(context.sender()), context.get("player"), BigDecimal.ZERO, Mutation.SET)));
    }

    private void registerHistory(Command.Builder<CommandSourceStack> base, ConfigSnapshot.Command command) {
        if (!command.enabled()) {
            return;
        }
        int maximumPage = this.configuration.get().reloadable().audit().maximumCommandPage();
        this.manager.command(base.literal(command.name(), command.aliases().toArray(String[]::new))
                .permission(command.permission()).commandDescription(Description.of(command.description()))
                .required("player", stringParser(), playerSuggestions())
                .optional("page", integerParser(1, maximumPage))
                .handler(context -> history(sender(context.sender()), context.get("player"),
                        context.<Integer>optional("page").orElse(1))));
    }

    private void registerReload(Command.Builder<CommandSourceStack> base, ConfigSnapshot.Command command) {
        if (command.enabled()) {
            this.manager.command(base.literal(command.name(), command.aliases().toArray(String[]::new))
                    .permission(command.permission()).commandDescription(Description.of(command.description()))
                    .handler(context -> this.reload.reload(sender(context.sender()))));
        }
    }

    private void registerStatus(Command.Builder<CommandSourceStack> base, ConfigSnapshot.Command command) {
        if (command.enabled()) {
            this.manager.command(base.literal(command.name(), command.aliases().toArray(String[]::new))
                    .permission(command.permission()).commandDescription(Description.of(command.description()))
                    .handler(context -> this.feedback.send(sender(context.sender()), "status", Map.of(
                            "state", Component.text(this.state.get().name()),
                            "redis", Component.text(this.redisState.get()),
                            "server", Component.text(this.configuration.get().restartRequired().serverId())))));
        }
    }

    private void ownBalance(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            this.feedback.send(sender, "player-only", Map.of());
            return;
        }
        this.points.balance(player.getUniqueId()).whenComplete((snapshot, failure) -> main(() -> {
            if (failure != null) {
                this.feedback.send(sender, "service-unavailable", Map.of());
            } else {
                this.feedback.send(sender, "balance", Map.of("amount", this.points.formatAmount(snapshot.balance())));
            }
        }));
    }

    private void resolveAndShowBalance(CommandSender sender, String name) {
        resolve(name).thenCompose(account -> account.map(value -> this.points.balance(value.playerId())
                        .thenApply(balance -> Optional.of(Map.entry(value, balance))))
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty())))
                .whenComplete((resolved, failure) -> main(() -> {
                    if (failure != null) {
                        this.feedback.send(sender, "service-unavailable", Map.of());
                    } else if (resolved.isEmpty()) {
                        this.feedback.send(sender, "unknown-player", Map.of());
                    } else {
                        var value = resolved.orElseThrow();
                        identity(value.getKey()).whenComplete((identity, identityFailure) -> main(() -> {
                            if (identityFailure != null) {
                                this.feedback.send(sender, "service-unavailable", Map.of());
                            } else {
                                this.feedback.send(sender, "balance-other", Map.of(
                                        "player", identity, "amount", this.points.formatAmount(value.getValue().balance())));
                            }
                        }));
                    }
                }));
    }

    private void mutate(CommandSender sender, String name, String input, Mutation mutation) {
        AmountParseResult parsed = this.points.parseAmount(input);
        if (!(parsed instanceof AmountParseResult.Success success) || (mutation != Mutation.SET && success.amount().signum() == 0)) {
            this.feedback.send(sender, "invalid-amount", Map.of());
            return;
        }
        mutateResolved(sender, name, success.amount(), mutation);
    }

    private void mutateResolved(CommandSender sender, String name, BigDecimal amount, Mutation mutation) {
        resolve(name).whenComplete((account, failure) -> {
            if (failure != null) {
                main(() -> this.feedback.send(sender, "service-unavailable", Map.of()));
                return;
            }
            if (account.isEmpty()) {
                main(() -> this.feedback.send(sender, "unknown-player", Map.of()));
                return;
            }
            AccountRecord target = account.orElseThrow();
            MutationContext context = new MutationContext(UUID.randomUUID(), Key.key("networkpoints:command_" + mutation.name().toLowerCase()),
                    sender instanceof Player player ? Optional.of(player.getUniqueId()) : Optional.empty(), Optional.of(name));
            CompletableFuture<MutationResult> operation = switch (mutation) {
                case CREDIT -> this.points.credit(new CreditRequest(target.playerId(), amount, context));
                case DEBIT -> this.points.debit(new DebitRequest(target.playerId(), amount, context));
                case SET -> this.points.setBalance(new SetBalanceRequest(target.playerId(), amount, context));
            };
            operation.whenComplete((result, operationFailure) -> main(() -> mutationResult(sender, target, result, operationFailure)));
        });
    }

    private void mutationResult(CommandSender sender, AccountRecord target, MutationResult result, Throwable failure) {
        if (failure != null) {
            this.feedback.send(sender, "service-unavailable", Map.of());
            return;
        }
        if (!result.success()) {
            this.feedback.send(sender, "mutation-" + result.status().name().toLowerCase().replace('_', '-'), Map.of());
            return;
        }
        identity(target).whenComplete((identity, identityFailure) -> main(() -> {
            if (identityFailure != null) {
                this.feedback.send(sender, "service-unavailable", Map.of());
            } else {
                this.feedback.send(sender, "mutation-success", Map.of(
                        "player", identity,
                        "amount", this.points.formatAmount(result.finalAmount().orElseThrow()),
                        "balance", this.points.formatAmount(result.after().orElseThrow().balance())));
            }
        }));
    }

    private void history(CommandSender sender, String name, int page) {
        ConfigSnapshot.Audit config = this.configuration.get().reloadable().audit();
        resolve(name).thenCompose(account -> account.map(value -> this.audit.history(
                        value.playerId(), page, config.commandPageSize(), config.maximumCommandPage())
                        .thenApply(records -> Optional.of(Map.entry(value, records))))
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty())))
                .whenComplete((result, failure) -> main(() -> {
                    if (failure != null) {
                        this.feedback.send(sender, "service-unavailable", Map.of());
                    } else if (result.isEmpty()) {
                        this.feedback.send(sender, "unknown-player", Map.of());
                    } else {
                        showHistory(sender, result.orElseThrow().getKey(), result.orElseThrow().getValue(), page);
                    }
                }));
    }

    private void showHistory(CommandSender sender, AccountRecord account, List<TransactionRecord> records, int page) {
        identity(account).whenComplete((identity, identityFailure) -> main(() -> {
            if (identityFailure != null) {
                this.feedback.send(sender, "service-unavailable", Map.of());
                return;
            }
            this.feedback.send(sender, "history-header", Map.of("player", identity, "page", Component.text(page)));
            if (records.isEmpty()) {
                this.feedback.send(sender, "history-empty", Map.of());
            }
            records.forEach(record -> this.feedback.send(sender, "history-entry", Map.of(
                    "time", Component.text(HISTORY_TIME.format(record.createdAt())),
                    "type", Component.text(record.kind().name()),
                    "delta", Component.text((record.delta().signum() >= 0 ? "+" : "-")
                            + this.points.formatAmountPlain(record.delta().abs(), com.stephanofer.networkpoints.api.amount.AmountDisplayMode.GROUPED)),
                    "balance", this.points.formatAmount(record.balanceAfter()),
                    "source", Component.text(record.source()))));
        }));
    }

    private CompletableFuture<Optional<AccountRecord>> resolve(String name) {
        Player online = this.plugin.getServer().getPlayerExact(name);
        if (online != null) {
            return this.accounts.find(online.getUniqueId());
        }
        return this.accounts.findByName(name);
    }

    private CompletableFuture<Component> identity(AccountRecord account) {
        Player online = this.plugin.getServer().getPlayer(account.playerId());
        return online == null ? this.identities.offline(account.playerId())
                : CompletableFuture.completedFuture(this.identities.online(online));
    }

    private SuggestionProvider<CommandSourceStack> playerSuggestions() {
        return SuggestionProvider.blockingStrings((context, input) -> {
            CommandSender sender = sender(context.sender());
            return this.plugin.getServer().getOnlinePlayers().stream()
                    .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                    .map(Player::getName).toList();
        });
    }

    private SuggestionProvider<CommandSourceStack> paymentSuggestions() {
        return SuggestionProvider.blockingStrings((context, input) -> {
            CommandSender sender = sender(context.sender());
            return this.plugin.getServer().getOnlinePlayers().stream()
                    .filter(player -> !(sender instanceof Player viewer)
                            || (!viewer.getUniqueId().equals(player.getUniqueId()) && viewer.canSee(player)))
                    .map(Player::getName).toList();
        });
    }

    private void registerExceptions() {
        this.manager.exceptionController().registerHandler(NoPermissionException.class,
                context -> this.feedback.send(sender(context.context().sender()), "no-permission", Map.of()));
        this.manager.exceptionController().registerHandler(InvalidSyntaxException.class,
                context -> this.feedback.send(sender(context.context().sender()), "invalid-command", Map.of()));
        this.manager.exceptionController().registerHandler(ArgumentParseException.class,
                context -> this.feedback.send(sender(context.context().sender()), "invalid-command", Map.of()));
    }

    private void main(Runnable action) {
        this.plugin.getServer().getScheduler().runTask(this.plugin, action);
    }

    private static CommandSender sender(CommandSourceStack source) {
        return source.getSender();
    }

    private enum Mutation {
        CREDIT, DEBIT, SET
    }

    @FunctionalInterface
    public interface ReloadAction {
        void reload(CommandSender sender);
    }
}
