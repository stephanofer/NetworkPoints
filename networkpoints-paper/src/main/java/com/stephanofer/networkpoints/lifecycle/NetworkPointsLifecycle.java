package com.stephanofer.networkpoints.lifecycle;

import com.hera.craftkit.database.Database;
import com.hera.craftkit.redis.RedisClient;
import com.hera.craftkit.redis.RedisStatusRegistration;
import com.stephanofer.networkpoints.account.AccountRepository;
import com.stephanofer.networkpoints.account.AccountStore;
import com.stephanofer.networkpoints.account.BalanceCache;
import com.stephanofer.networkpoints.account.EconomicEngine;
import com.stephanofer.networkpoints.amount.AmountFormatter;
import com.stephanofer.networkpoints.amount.AmountParser;
import com.stephanofer.networkpoints.amount.CompactTier;
import com.stephanofer.networkpoints.api.NetworkPointsService;
import com.stephanofer.networkpoints.api.amount.AmountDisplayMode;
import com.stephanofer.networkpoints.api.event.PlayerPointsReadyEvent;
import com.stephanofer.networkpoints.award.AwardCalculator;
import com.stephanofer.networkpoints.award.NetworkBoostersAwardCalculator;
import com.stephanofer.networkpoints.award.NeutralAwardCalculator;
import com.stephanofer.networkpoints.config.ConfigSnapshot;
import com.stephanofer.networkpoints.config.NetworkPointsConfig;
import com.stephanofer.networkpoints.command.PointsCommandController;
import com.stephanofer.networkpoints.feedback.FeedbackAction;
import com.stephanofer.networkpoints.feedback.FeedbackCompiler;
import com.stephanofer.networkpoints.feedback.FeedbackService;
import com.stephanofer.networkpoints.identity.PlayerIdentityService;
import com.stephanofer.networkpoints.localization.LocalizedCatalog;
import com.stephanofer.networkpoints.placeholder.NetworkPointsExpansion;
import com.stephanofer.networkpoints.placeholder.PointsPlaceholderRenderer;
import com.stephanofer.networkpoints.persistence.AuditStore;
import com.stephanofer.networkpoints.persistence.DatabaseFactory;
import com.stephanofer.networkpoints.persistence.OperationRepository;
import com.stephanofer.networkpoints.persistence.TransactionRepository;
import com.stephanofer.networkpoints.payment.PaymentController;
import com.stephanofer.networkpoints.payment.PaymentNotifications;
import com.stephanofer.networkpoints.service.DurableNetworkPointsService;
import com.stephanofer.networkpoints.service.PaperEventDispatcher;
import com.stephanofer.networkpoints.service.PostCommitCoordinator;
import com.stephanofer.networkpoints.synchronization.BalanceInvalidationCodec;
import com.stephanofer.networkpoints.synchronization.PointsSynchronization;
import com.stephanofer.networkpoints.synchronization.RedisFactory;
import com.stephanofer.networkboosters.api.NetworkBoostersService;
import com.stephanofer.networkplayersettings.assets.api.CountryFlagService;
import com.stephanofer.networkplayersettings.settings.api.PlayerSettingsService;
import com.stephanofer.networkplayersettings.settings.api.PlayerStyleService;
import com.stephanofer.networkplayersettings.settings.event.PlayerSettingChangeEvent;
import com.stephanofer.networkplayersettings.settings.event.PlayerSettingsReadyEvent;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.command.CommandSender;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.incendo.cloud.paper.PaperCommandManager;

public final class NetworkPointsLifecycle implements Listener {

    private final JavaPlugin plugin;
    private final NetworkPointsConfig configuration;
    private final PaperCommandManager.Bootstrapped<CommandSourceStack> commandManager;
    private final AtomicReference<LifecycleState> state = new AtomicReference<>(LifecycleState.NEW);
    private final AtomicBoolean reloadInProgress = new AtomicBoolean();
    private final PlayerPreloadGuard preloads = new PlayerPreloadGuard();
    private Database database;
    private AccountStore accountStore;
    private AuditStore auditStore;
    private BalanceCache balanceCache;
    private DurableNetworkPointsService service;
    private RedisClient redis;
    private PointsSynchronization synchronization;
    private RedisStatusRegistration redisStatus;
    private BukkitTask auditTask;
    private PlayerIdentityService identities;
    private FeedbackService feedback;
    private NetworkPointsExpansion expansion;
    private PaymentController payments;
    private PaymentNotifications paymentNotifications;

    public NetworkPointsLifecycle(JavaPlugin plugin, PaperCommandManager.Bootstrapped<CommandSourceStack> commandManager) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.commandManager = Objects.requireNonNull(commandManager, "commandManager");
        this.configuration = new NetworkPointsConfig(plugin.getDataPath());
    }

    public void start() {
        if (!this.state.compareAndSet(LifecycleState.NEW, LifecycleState.STARTING)) {
            throw new IllegalStateException("Lifecycle can only be started once");
        }

        try {
            ConfigSnapshot snapshot = this.configuration.start();
            startDurableCore(snapshot);
            this.plugin.getServer().getServicesManager().register(
                    NetworkPointsService.class, this.service, this.plugin, ServicePriority.Normal);
            this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
            startPaperExperience(snapshot);
            this.plugin.getServer().getOnlinePlayers().forEach(
                    player -> preload(player.getUniqueId(), player.getName()));
            scheduleAuditCleanup(snapshot.reloadable().audit());
            this.plugin.getComponentLogger().info(
                "NetworkPoints durable economy started for server {}.",
                snapshot.restartRequired().serverId()
            );
            if (!this.state.compareAndSet(LifecycleState.STARTING, LifecycleState.RUNNING)) {
                throw new IllegalStateException("Lifecycle left STARTING during startup");
            }
        } catch (Throwable failure) {
            this.state.set(LifecycleState.FAILED);
            try {
                this.plugin.getComponentLogger().error("NetworkPoints could not start.", failure);
            } finally {
                try {
                    closeRuntime();
                } finally {
                    this.plugin.getServer().getPluginManager().disablePlugin(this.plugin);
                }
            }
        }
    }

    public void stop() {
        while (true) {
            LifecycleState current = this.state.get();
            if (current == LifecycleState.STOPPED
                    || current == LifecycleState.STOPPING) {
                return;
            }
            if (this.state.compareAndSet(current, LifecycleState.STOPPING)) {
                try {
                    DurableNetworkPointsService currentService = this.service;
                    if (currentService != null) {
                        shutdownStep("service mutation shutdown", currentService::stopAcceptingMutations);
                    }
                    BukkitTask currentAuditTask = this.auditTask;
                    this.auditTask = null;
                    if (currentAuditTask != null) {
                        shutdownStep("audit task cancellation", currentAuditTask::cancel);
                    }
                    awaitOperations();
                } finally {
                    closeRuntime();
                    this.state.set(LifecycleState.STOPPED);
                }
                return;
            }
        }
    }

    public LifecycleState state() {
        return this.state.get();
    }

    public NetworkPointsConfig configuration() {
        return this.configuration;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (this.identities != null) {
            this.identities.invalidate(event.getPlayer().getUniqueId());
        }
        preload(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        java.util.UUID playerId = event.getPlayer().getUniqueId();
        BalanceCache cache = this.balanceCache;
        this.preloads.leave(playerId, () -> {
            if (cache != null) {
                cache.invalidate(playerId);
            }
        });
        if (this.feedback != null) {
            this.feedback.clear(event.getPlayer());
        }
        if (this.identities != null) {
            this.identities.invalidate(event.getPlayer().getUniqueId());
        }
        if (this.payments != null) {
            this.payments.clear(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerSettingChange(PlayerSettingChangeEvent event) {
        if (this.identities != null) {
            this.identities.invalidate(event.playerId());
        }
    }

    @EventHandler
    public void onPlayerSettingsReady(PlayerSettingsReadyEvent event) {
        if (this.feedback != null) {
            this.feedback.ready(event.player());
        }
    }

    private void startDurableCore(ConfigSnapshot snapshot) {
        this.database = DatabaseFactory.create(
                snapshot.restartRequired().database(), this.plugin.getClass().getClassLoader());
        this.database.migrate().join();

        AccountRepository accounts = new AccountRepository();
        TransactionRepository transactions = new TransactionRepository();
        OperationRepository operations = new OperationRepository();
        this.accountStore = new AccountStore(this.database, accounts);
        this.auditStore = new AuditStore(this.database, transactions, Clock.systemUTC());
        ConfigSnapshot.Cache cacheConfig = snapshot.reloadable().cache();
        this.balanceCache = new BalanceCache(
                cacheConfig.maximumSize(),
                Duration.ofSeconds(cacheConfig.refreshAfterWriteSeconds()),
                Duration.ofMinutes(cacheConfig.expireAfterAccessMinutes()),
                playerId -> this.accountStore.find(playerId).thenApply(account -> account
                        .orElseThrow(() -> new NoSuchElementException("Points account does not exist"))
                        .snapshot()));
        AwardCalculator awardCalculator = awardCalculator(snapshot.restartRequired().integrations());
        EconomicEngine engine = new EconomicEngine(
                this.database,
                accounts,
                transactions,
                operations,
                awardCalculator,
                snapshot.restartRequired().monetaryPolicy().maximumBalance(),
                snapshot.restartRequired().serverId());
        ConfigSnapshot.Reloadable reloadable = snapshot.reloadable();
        AmountParser parser = new AmountParser(
                java.math.BigDecimal.ZERO,
                snapshot.restartRequired().monetaryPolicy().maximumBalance(),
                snapshot.restartRequired().monetaryPolicy().maximumBalance(),
                reloadable.amountInput().suffixes());
        List<CompactTier> tiers = reloadable.amountFormat().compactTiers().stream()
                .map(tier -> new CompactTier(tier.threshold(), tier.pattern(), tier.suffix(), tier.display()))
                .toList();
        AmountFormatter formatter = new AmountFormatter(
                reloadable.amountFormat().groupedPattern(),
                reloadable.amountFormat().groupingSeparator(),
                reloadable.amountFormat().decimalSeparator(),
                tiers);
        AmountDisplayMode defaultMode = AmountDisplayMode.valueOf(reloadable.amountFormat().defaultMode().name());
        this.redis = RedisFactory.create(snapshot.restartRequired().redis(), snapshot.restartRequired().serverId());
        this.synchronization = new PointsSynchronization(
                this.redis,
                snapshot.restartRequired().serverId(),
                new BalanceInvalidationCodec(),
                this.balanceCache,
                failure -> this.plugin.getComponentLogger().warn("NetworkPoints Redis synchronization failed.", failure));
        this.redisStatus = this.redis.observeOperationalStatus(status ->
                this.plugin.getComponentLogger().info("NetworkPoints Redis state changed to {}.", status.state()));
        PostCommitCoordinator postCommit = new PostCommitCoordinator(
                this.balanceCache, this.synchronization, new PaperEventDispatcher(this.plugin));
        this.service = new DurableNetworkPointsService(
                engine, this.balanceCache, postCommit, parser, formatter, defaultMode, reloadable.currency());
    }

    private void startPaperExperience(ConfigSnapshot snapshot) {
        PlayerSettingsService settings = requiredService(PlayerSettingsService.class, "PlayerSettingsService");
        PlayerStyleService styles = requiredService(PlayerStyleService.class, "PlayerStyleService");
        CountryFlagService flags = requiredService(CountryFlagService.class, "CountryFlagService");
        LuckPerms luckPerms = LuckPermsProvider.get();
        LocalizedCatalog<FeedbackAction> catalog = new FeedbackCompiler().compile(snapshot.reloadable().messages());
        this.feedback = new FeedbackService(this.plugin, settings, catalog);
        this.identities = new PlayerIdentityService(
                this.plugin, this.accountStore, styles, flags, luckPerms, snapshot.reloadable().identity(),
                Duration.ofMinutes(snapshot.reloadable().cache().expireAfterAccessMinutes()));
        this.paymentNotifications = new PaymentNotifications(
                this.plugin, this.redis, snapshot.restartRequired().serverId(), this.service, this.identities,
                this.feedback, failure -> this.plugin.getComponentLogger().warn(
                        "NetworkPoints payment notification failed.", failure));
        this.payments = new PaymentController(
                this.plugin, this.service, this.accountStore, this.identities, this.feedback,
                this.configuration::snapshot, this.paymentNotifications);
        new PointsCommandController(
                this.plugin, this.commandManager, this.service, this.accountStore, this.auditStore, this.identities,
                this.feedback, this.configuration::snapshot, this::state, this::redisState, this::reload,
                this.payments).register();
        ConfigSnapshot.Integrations integrations = snapshot.restartRequired().integrations();
        if (integrations.placeholderApi() && this.plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            this.expansion = new NetworkPointsExpansion(this.plugin,
                    new PointsPlaceholderRenderer(this.service, this.configuration::snapshot));
            if (!this.expansion.register()) {
                throw new IllegalStateException("PlaceholderAPI rejected the NetworkPoints expansion");
            }
        }
    }

    private <T> T requiredService(Class<T> type, String name) {
        T service = this.plugin.getServer().getServicesManager().load(type);
        if (service == null) {
            throw new IllegalStateException(name + " is unavailable");
        }
        return service;
    }

    private String redisState() {
        RedisClient current = this.redis;
        return current == null ? "CLOSED" : current.operationalStatus().state().name();
    }

    private void reload(CommandSender sender) {
        if (!this.reloadInProgress.compareAndSet(false, true)) {
            this.feedback.send(sender, "reload-failed", Map.of());
            return;
        }
        try {
            this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, () -> prepareReload(sender));
        } catch (RuntimeException exception) {
            rejectReload(sender, exception);
        }
    }

    private void prepareReload(CommandSender sender) {
        try {
            AtomicReference<LocalizedCatalog<FeedbackAction>> catalog = new AtomicReference<>();
            NetworkPointsConfig.ReloadCandidate candidate = this.configuration.prepareReload(reloadable ->
                    catalog.set(new FeedbackCompiler().compile(reloadable.messages())));
            PresentationUpdate presentation = presentation(candidate.published());
            this.plugin.getServer().getScheduler().runTask(this.plugin,
                    () -> applyReload(sender, candidate, catalog.get(), presentation));
        } catch (Exception exception) {
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> rejectReload(sender, exception));
        }
    }

    private void applyReload(CommandSender sender, NetworkPointsConfig.ReloadCandidate candidate,
                             LocalizedCatalog<FeedbackAction> catalog, PresentationUpdate presentation) {
        if (this.state.get() != LifecycleState.RUNNING) {
            this.reloadInProgress.set(false);
            return;
        }
        try {
            ConfigSnapshot snapshot = candidate.published();
            BukkitTask replacementAuditTask = createAuditCleanupTask(snapshot.reloadable().audit());
            this.feedback.update(catalog);
            this.payments.clearSessions();
            this.identities.update(snapshot.reloadable().identity());
            this.service.updatePresentation(presentation.parser(), presentation.formatter(),
                    presentation.mode(), snapshot.reloadable().currency());
            BukkitTask previousAuditTask = this.auditTask;
            this.auditTask = replacementAuditTask;
            if (previousAuditTask != null) {
                previousAuditTask.cancel();
            }
            this.configuration.publish(candidate);
            String message = snapshot.restartRequiredChanges().isEmpty()
                    ? "reload-success"
                    : "reload-success-restart-required";
            this.feedback.send(sender, message, Map.of("restart_changes",
                    net.kyori.adventure.text.Component.text(String.join(", ", snapshot.restartRequiredChanges()))));
        } catch (Exception exception) {
            rejectReload(sender, exception);
            return;
        }
        this.reloadInProgress.set(false);
    }

    private void rejectReload(CommandSender sender, Exception exception) {
        this.reloadInProgress.set(false);
        this.plugin.getComponentLogger().warn("NetworkPoints reload rejected.", exception);
        this.feedback.send(sender, "reload-failed", Map.of());
    }

    private PresentationUpdate presentation(ConfigSnapshot snapshot) {
        ConfigSnapshot.Reloadable reloadable = snapshot.reloadable();
        AmountParser parser = new AmountParser(java.math.BigDecimal.ZERO,
                snapshot.restartRequired().monetaryPolicy().maximumBalance(),
                snapshot.restartRequired().monetaryPolicy().maximumBalance(), reloadable.amountInput().suffixes());
        List<CompactTier> tiers = reloadable.amountFormat().compactTiers().stream()
                .map(tier -> new CompactTier(tier.threshold(), tier.pattern(), tier.suffix(), tier.display())).toList();
        AmountFormatter formatter = new AmountFormatter(reloadable.amountFormat().groupedPattern(),
                reloadable.amountFormat().groupingSeparator(), reloadable.amountFormat().decimalSeparator(), tiers);
        return new PresentationUpdate(parser, formatter,
                AmountDisplayMode.valueOf(reloadable.amountFormat().defaultMode().name()));
    }

    private AwardCalculator awardCalculator(ConfigSnapshot.Integrations integrations) {
        if (!integrations.networkBoosters()) {
            return new NeutralAwardCalculator();
        }
        NetworkBoostersService boosters = this.plugin.getServer().getServicesManager().load(NetworkBoostersService.class);
        if (boosters == null) {
            throw new IllegalStateException("NetworkBoosters integration is enabled but its service is unavailable");
        }
        return new NetworkBoostersAwardCalculator(boosters);
    }

    private void preload(java.util.UUID playerId, String name) {
        AccountStore store = this.accountStore;
        LifecycleState currentState = this.state.get();
        if (store == null || currentState == LifecycleState.STOPPING || currentState == LifecycleState.STOPPED
                || currentState == LifecycleState.FAILED) {
            return;
        }
        long generation = this.preloads.begin(playerId);
        store.ensureAccount(playerId, name).thenAccept(account -> {
            BalanceCache cache = this.balanceCache;
            if (cache == null) {
                return;
            }
            var published = new AtomicReference<com.stephanofer.networkpoints.api.balance.BalanceSnapshot>();
            if (!this.preloads.runIfCurrent(playerId, generation,
                    () -> published.set(cache.publish(account.snapshot())))) {
                return;
            }
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
                if (this.state.get() == LifecycleState.RUNNING
                        && this.preloads.isCurrent(playerId, generation)
                        && this.plugin.getServer().getPlayer(playerId) != null
                        && published.get() != null) {
                    cache.getIfPresent(playerId).ifPresent(snapshot -> this.plugin.getServer()
                            .getPluginManager().callEvent(new PlayerPointsReadyEvent(snapshot)));
                }
            });
        }).exceptionally(failure -> {
            this.plugin.getComponentLogger().error("Could not create or update points account for {}.", name, failure);
            return null;
        });
    }

    private void awaitOperations() {
        if (this.service == null) {
            return;
        }
        long timeout = this.configuration.snapshot().restartRequired().database().shutdownTimeoutMillis();
        try {
            this.service.inFlightOperations().get(timeout, TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            this.plugin.getComponentLogger().warn("Timed out waiting for NetworkPoints operations to finish.", exception);
        }
    }

    private void closeRuntime() {
        this.preloads.clear();
        BukkitTask currentAuditTask = this.auditTask;
        this.auditTask = null;
        if (currentAuditTask != null) shutdownStep("audit task cancellation", currentAuditTask::cancel);
        DurableNetworkPointsService currentService = this.service;
        this.service = null;
        if (currentService != null) {
            shutdownStep("service mutation shutdown", currentService::stopAcceptingMutations);
            shutdownStep("service unregistration", () -> this.plugin.getServer().getServicesManager()
                    .unregister(NetworkPointsService.class, currentService));
        }
        NetworkPointsExpansion currentExpansion = this.expansion;
        this.expansion = null;
        if (currentExpansion != null) shutdownStep("PlaceholderAPI expansion shutdown", currentExpansion::unregister);
        PaymentController currentPayments = this.payments;
        this.payments = null;
        if (currentPayments != null) shutdownStep("payments shutdown", currentPayments::close);
        PaymentNotifications currentNotifications = this.paymentNotifications;
        this.paymentNotifications = null;
        if (currentNotifications != null) shutdownStep("payment notification shutdown", currentNotifications::close);
        FeedbackService currentFeedback = this.feedback;
        this.feedback = null;
        if (currentFeedback != null) shutdownStep("feedback shutdown", currentFeedback::close);
        PlayerIdentityService currentIdentities = this.identities;
        this.identities = null;
        if (currentIdentities != null) shutdownStep("identity shutdown", currentIdentities::close);
        RedisStatusRegistration currentRedisStatus = this.redisStatus;
        this.redisStatus = null;
        if (currentRedisStatus != null) shutdownStep("Redis status shutdown", currentRedisStatus::close);
        PointsSynchronization currentSynchronization = this.synchronization;
        this.synchronization = null;
        if (currentSynchronization != null) shutdownStep("Redis synchronization shutdown", currentSynchronization::close);
        RedisClient currentRedis = this.redis;
        this.redis = null;
        if (currentRedis != null) shutdownStep("Redis shutdown", currentRedis::close);
        closeDatabase();
        BalanceCache currentCache = this.balanceCache;
        this.balanceCache = null;
        if (currentCache != null) shutdownStep("balance cache shutdown", currentCache::close);
    }

    private void scheduleAuditCleanup(ConfigSnapshot.Audit audit) {
        this.auditTask = createAuditCleanupTask(audit);
    }

    private BukkitTask createAuditCleanupTask(ConfigSnapshot.Audit audit) {
        long intervalTicks = Math.multiplyExact(audit.cleanupIntervalHours(), 72_000L);
        return this.plugin.getServer().getScheduler().runTaskTimer(
                this.plugin, () -> cleanupAuditBatch(audit), intervalTicks, intervalTicks);
    }

    private void cleanupAuditBatch(ConfigSnapshot.Audit audit) {
        this.auditStore.cleanup(audit.retentionDays(), audit.cleanupBatchSize())
                .thenAccept(deleted -> {
                    if (deleted == audit.cleanupBatchSize() && this.state.get() == LifecycleState.RUNNING) {
                        cleanupAuditBatch(audit);
                    }
                })
                .exceptionally(failure -> {
                    this.plugin.getComponentLogger().warn("NetworkPoints audit cleanup failed.", failure);
                    return null;
                });
    }

    private void closeDatabase() {
        Database current = this.database;
        this.database = null;
        if (current != null) {
            shutdownStep("database shutdown", current::close);
        }
    }

    private void shutdownStep(String resource, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            try {
                this.plugin.getComponentLogger().error("NetworkPoints {} failed.", resource, failure);
            } catch (Throwable ignored) {
                // Shutdown must continue even if logging infrastructure is already unavailable.
            }
        }
    }

    private record PresentationUpdate(AmountParser parser, AmountFormatter formatter, AmountDisplayMode mode) {
    }
}
