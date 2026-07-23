package com.stephanofer.networkpoints.command;

import com.stephanofer.networkpoints.account.AccountRecord;
import com.stephanofer.networkpoints.account.AccountStore;
import com.stephanofer.networkpoints.api.NetworkPointsService;
import com.stephanofer.networkpoints.api.amount.AmountParseResult;
import com.stephanofer.networkpoints.api.amount.AmountDisplayMode;
import com.stephanofer.networkpoints.api.request.CreditRequest;
import com.stephanofer.networkpoints.api.request.AwardRequest;
import com.stephanofer.networkpoints.api.request.DebitRequest;
import com.stephanofer.networkpoints.api.request.SetBalanceRequest;
import com.stephanofer.networkpoints.api.result.MutationResult;
import com.stephanofer.networkpoints.api.source.MutationContext;
import com.stephanofer.networkpoints.config.ConfigSnapshot;
import com.stephanofer.networkpoints.feedback.FeedbackService;
import com.stephanofer.networkpoints.identity.PlayerIdentityService;
import com.stephanofer.networkpoints.lifecycle.LifecycleState;
import com.stephanofer.networkpoints.persistence.AuditStore;
import com.stephanofer.networkpoints.persistence.OperationRecord;
import com.stephanofer.networkpoints.persistence.OperationStore;
import com.stephanofer.networkpoints.persistence.TransactionRecord;
import com.stephanofer.networkpoints.payment.PaymentController;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.HashMap;
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
import com.stephanofer.networkboosters.api.NetworkBoostersService;
import org.incendo.cloud.Command;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.exception.ArgumentParseException;
import org.incendo.cloud.exception.CommandExecutionException;
import org.incendo.cloud.exception.InvalidSyntaxException;
import org.incendo.cloud.exception.NoPermissionException;
import org.incendo.cloud.minecraft.extras.MinecraftExceptionHandler;
import org.incendo.cloud.paper.PaperCommandManager;
import org.incendo.cloud.suggestion.SuggestionProvider;

import static org.incendo.cloud.parser.standard.IntegerParser.integerParser;
import static org.incendo.cloud.parser.standard.StringParser.stringParser;

public final class PointsCommandController {
    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC);
    private static final java.util.regex.Pattern TEST_GAME_ID = java.util.regex.Pattern.compile(
            "[a-z0-9][a-z0-9._-]{0,63}");
    private static final String DEFAULT_TEST_GAME_ID = "networkpoints-test";

    private final JavaPlugin plugin;
    private final PaperCommandManager<CommandSourceStack> manager;
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
    private final AdministrativeNotifications administrativeNotifications;
    private final OperationStore operations;
    private final NetworkBoostersService boosters;

    public PointsCommandController(JavaPlugin plugin, PaperCommandManager<CommandSourceStack> manager,
                                   NetworkPointsService points, AccountStore accounts, AuditStore audit,
                                   PlayerIdentityService identities, FeedbackService feedback,
                                     Supplier<ConfigSnapshot> configuration, Supplier<LifecycleState> state,
                                     Supplier<String> redisState, ReloadAction reload, PaymentController payments,
                                     AdministrativeNotifications administrativeNotifications,
                                     OperationStore operations, NetworkBoostersService boosters) {
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
        this.administrativeNotifications = administrativeNotifications;
        this.operations = operations;
        this.boosters = boosters;
    }

    public void register() {
        ConfigSnapshot.Commands commands = this.configuration.get().restartRequired().commands();
        ConfigSnapshot.Command root = commands.root();
        if (!root.enabled()) {
            return;
        }
        Command.Builder<CommandSourceStack> base = this.manager.commandBuilder(
                root.name(), root.aliases().toArray(String[]::new))
                .commandDescription(Description.of(root.description()));
        this.manager.command(base.permission(root.permission())
                .handler(context -> ownBalance(sender(context.sender()))));
        registerBalance(base, commands.entries().get("balance"));
        registerPay(base, commands.entries().get("pay"));
        registerMutation(base, commands.entries().get("give"), Mutation.CREDIT);
        registerMutation(base, commands.entries().get("take"), Mutation.DEBIT);
        registerMutation(base, commands.entries().get("set"), Mutation.SET);
        registerReset(base, commands.entries().get("reset"));
        registerHistory(base, commands.entries().get("history"));
        registerTestAward(base, commands.entries().get("test-award"));
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
                .handler(context -> mutateResolved(sender(context.sender()), context.get("player"), BigDecimal.ZERO, Mutation.RESET)));
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

    private void registerTestAward(Command.Builder<CommandSourceStack> base, ConfigSnapshot.Command command) {
        if (!command.enabled()) {
            return;
        }
        this.manager.command(base.literal(command.name(), command.aliases().toArray(String[]::new))
                .permission(command.permission()).commandDescription(Description.of(command.description()))
                .required("player", stringParser(), playerSuggestions())
                .required("amount", stringParser())
                .optional("game", stringParser())
                .handler(context -> testAward(sender(context.sender()), context.get("player"), context.get("amount"),
                        context.<String>optional("game").orElse(DEFAULT_TEST_GAME_ID))));
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
                sendBalance(sender, "balance", snapshot.balance(), Map.of());
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
                                sendBalance(sender, "balance-other", value.getValue().balance(),
                                        Map.of("player", identity));
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

    private void sendBalance(CommandSender sender, String key, BigDecimal balance,
                             Map<String, Component> additionalValues) {
        String compact = this.points.formatAmountPlain(balance, AmountDisplayMode.COMPACT);
        String grouped = this.points.formatAmountPlain(balance, AmountDisplayMode.GROUPED);
        boolean abbreviated = this.configuration.get().reloadable().amountFormat().defaultMode()
                == ConfigSnapshot.DisplayMode.COMPACT && !compact.equals(grouped);
        Map<String, Component> values = new HashMap<>(additionalValues);
        values.put("amount", this.points.formatAmount(balance));
        values.put("exact_amount", Component.text(grouped));
        this.feedback.send(sender, abbreviated ? key + "-compact-hover" : key, Map.copyOf(values));
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
                case SET, RESET -> this.points.setBalance(new SetBalanceRequest(target.playerId(), amount, context));
            };
            operation.whenComplete((result, operationFailure) ->
                    main(() -> mutationResult(sender, target, mutation, result, operationFailure)));
        });
    }

    private void mutationResult(CommandSender sender, AccountRecord target, Mutation mutation,
                                MutationResult result, Throwable failure) {
        if (failure != null) {
            this.feedback.send(sender, "service-unavailable", Map.of());
            return;
        }
        if (!result.success()) {
            this.feedback.send(sender, "mutation-" + result.status().name().toLowerCase().replace('_', '-'), Map.of());
            return;
        }
        BigDecimal amount = result.finalAmount().orElseThrow();
        BigDecimal balance = result.after().orElseThrow().balance();
        if (!result.replayed()) {
            this.administrativeNotifications.publish(new AdministrativeNotification(
                    result.operationId(), this.configuration.get().restartRequired().serverId(), mutation.notificationOperation(),
                    sender instanceof Player player ? Optional.of(player.getUniqueId()) : Optional.empty(),
                    sender instanceof Player player ? player.getName() : "Console", target.playerId(), amount, balance));
        }
        identity(target).whenComplete((identity, identityFailure) -> main(() -> {
            Component renderedIdentity = identity;
            if (identityFailure != null) {
                this.plugin.getComponentLogger().warn(Component.text(
                        "Could not render player identity after a successful points mutation; using last known name"),
                        identityFailure);
                renderedIdentity = Component.text(target.lastKnownName());
            }
            this.feedback.send(sender, "mutation-" + mutation.messageKey() + "-executed", Map.of(
                    "player", renderedIdentity,
                    "amount", this.points.formatAmount(amount),
                    "balance", this.points.formatAmount(balance)));
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

    private void testAward(CommandSender sender, String name, String input, String gameId) {
        AmountParseResult parsed = this.points.parseAmount(input);
        if (!(parsed instanceof AmountParseResult.Success success) || success.amount().signum() <= 0) {
            this.feedback.send(sender, "invalid-amount", Map.of());
            return;
        }
        if (!TEST_GAME_ID.matcher(gameId).matches()) {
            this.feedback.send(sender, "test-award-invalid-game", Map.of());
            return;
        }
        resolve(name).whenComplete((account, resolveFailure) -> {
            if (resolveFailure != null) {
                main(() -> this.feedback.send(sender, "service-unavailable", Map.of()));
                return;
            }
            if (account.isEmpty()) {
                main(() -> this.feedback.send(sender, "unknown-player", Map.of()));
                return;
            }
            AccountRecord target = account.orElseThrow();
            UUID operationId = UUID.randomUUID();
            String serverId = this.configuration.get().restartRequired().serverId();
            MutationContext mutationContext = new MutationContext(operationId, Key.key("networkpoints:test_consumer"),
                    sender instanceof Player player ? Optional.of(player.getUniqueId()) : Optional.empty(),
                    Optional.of("test-award:" + gameId));
            AwardRequest request = new AwardRequest(target.playerId(), success.amount(), gameId, serverId, mutationContext);
            boolean readyBefore = this.boosters != null && this.boosters.isReady(target.playerId());
            this.points.award(request).whenComplete((result, awardFailure) -> {
                if (awardFailure != null) {
                    main(() -> this.feedback.send(sender, "service-unavailable", Map.of()));
                    return;
                }
                if (!result.success()) {
                    main(() -> showTestAwardFailure(sender, target, request, result.status().name(), readyBefore));
                    return;
                }
                this.operations.find(operationId).whenComplete((operation, detailFailure) -> main(() -> {
                    if (detailFailure != null || operation.isEmpty()) {
                        this.plugin.getComponentLogger().warn(
                                "Could not load persisted details for test award {}.", operationId, detailFailure);
                        showTestAwardFailure(sender, target, request, "DIAGNOSTIC_READ_FAILED", readyBefore);
                        return;
                    }
                    showTestAwardSuccess(sender, target, operation.orElseThrow(), readyBefore, result.replayed());
                }));
            });
        });
    }

    private void showTestAwardFailure(CommandSender sender, AccountRecord target, AwardRequest request,
                                      String status, boolean readyBefore) {
        this.feedback.send(sender, "test-award-failed", Map.of(
                "player", Component.text(target.lastKnownName()),
                "status", Component.text(status),
                "integration", Component.text(this.boosters == null ? "DISABLED" : "ENABLED"),
                "ready", Component.text(this.boosters == null ? "NOT_APPLICABLE" : Boolean.toString(readyBefore)),
                "base", this.points.formatAmount(request.amount()),
                "game", Component.text(request.gameId()),
                "server", Component.text(request.serverId()),
                "operation", Component.text(request.context().operationId().toString())));
    }

    private void showTestAwardSuccess(CommandSender sender, AccountRecord target, OperationRecord operation,
                                      boolean readyBefore, boolean replayed) {
        this.feedback.send(sender, "test-award-header", Map.of(
                "player", Component.text(target.lastKnownName()),
                "operation", Component.text(operation.operationId().toString())));
        this.feedback.send(sender, "test-award-context", Map.of(
                "integration", Component.text(this.boosters == null ? "DISABLED" : "ENABLED"),
                "ready", Component.text(this.boosters == null ? "NOT_APPLICABLE" : Boolean.toString(readyBefore)),
                "game", Component.text(operation.awardGameId().orElseThrow()),
                "server", Component.text(operation.awardServerId().orElseThrow()),
                "source", Component.text(operation.context().source().asString()),
                "replayed", Component.text(Boolean.toString(replayed))));
        this.feedback.send(sender, "test-award-result", Map.of(
                "base", this.points.formatAmount(operation.baseAmount().orElseThrow()),
                "multiplier", Component.text(operation.multiplier().orElseThrow().stripTrailingZeros().toPlainString()),
                "final", this.points.formatAmount(operation.finalAmount().orElseThrow()),
                "delta", this.points.formatAmount(operation.delta().orElseThrow()),
                "before", this.points.formatAmount(operation.accountBefore().orElseThrow().balance()),
                "after", this.points.formatAmount(operation.accountAfter().orElseThrow().balance()),
                "count", Component.text(operation.appliedBoosts().size())));
        if (operation.appliedBoosts().isEmpty()) {
            this.feedback.send(sender, "test-award-no-boosters", Map.of());
            return;
        }
        operation.appliedBoosts().forEach(boost -> this.feedback.send(sender, "test-award-booster", Map.of(
                "booster", Component.text(boost.boosterId()),
                "group", Component.text(boost.activationGroup()),
                "multiplier", Component.text(boost.multiplier().stripTrailingZeros().toPlainString()),
                "activation", Component.text(boost.activationId().toString()))));
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
        MinecraftExceptionHandler.<CommandSourceStack>create(source -> source.getSender())
                .handler(NoPermissionException.class, (formatter, context) -> {
                    this.feedback.send(sender(context.context().sender()), "no-permission", Map.of());
                    return null;
                })
                .handler(InvalidSyntaxException.class, (formatter, context) -> {
                    this.feedback.send(sender(context.context().sender()), "invalid-command", Map.of());
                    return null;
                })
                .handler(ArgumentParseException.class, (formatter, context) -> {
                    this.feedback.send(sender(context.context().sender()), "invalid-command", Map.of());
                    return null;
                })
                .handler(CommandExecutionException.class, (formatter, context) -> {
                    this.plugin.getComponentLogger().error(
                            "Unhandled exception while executing a NetworkPoints command.",
                            context.exception().getCause());
                    this.feedback.send(sender(context.context().sender()), "service-unavailable", Map.of());
                    return null;
                })
                .registerTo(this.manager);
    }

    private void main(Runnable action) {
        this.plugin.getServer().getScheduler().runTask(this.plugin, action);
    }

    private static CommandSender sender(CommandSourceStack source) {
        return source.getSender();
    }

    private enum Mutation {
        CREDIT("give", AdministrativeNotification.Operation.GIVE),
        DEBIT("take", AdministrativeNotification.Operation.TAKE),
        SET("set", AdministrativeNotification.Operation.SET),
        RESET("reset", AdministrativeNotification.Operation.RESET);

        private final String messageKey;
        private final AdministrativeNotification.Operation notificationOperation;

        Mutation(String messageKey, AdministrativeNotification.Operation notificationOperation) {
            this.messageKey = messageKey;
            this.notificationOperation = notificationOperation;
        }

        private String messageKey() {
            return this.messageKey;
        }

        private AdministrativeNotification.Operation notificationOperation() {
            return this.notificationOperation;
        }
    }

    @FunctionalInterface
    public interface ReloadAction {
        void reload(CommandSender sender);
    }
}
