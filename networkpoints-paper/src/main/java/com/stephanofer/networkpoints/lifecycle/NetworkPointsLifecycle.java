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
import com.stephanofer.networkpoints.persistence.TransactionRepository;
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
            if (!this.state.compareAndSet(LifecycleState.STARTING, LifecycleState.RUNNING)) {
                return;
            }
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
        } catch (Exception exception) {
            if (!this.state.compareAndSet(LifecycleState.STARTING, LifecycleState.FAILED)) {
                return;
            }
            this.plugin.getComponentLogger().error("NetworkPoints could not start.", exception);
            closeRuntime();
            this.plugin.getServer().getPluginManager().disablePlugin(this.plugin);
        }
    }

    public void stop() {
        while (true) {
            LifecycleState current = this.state.get();
            if (current == LifecycleState.STOPPED
                    || current == LifecycleState.STOPPING
                    || current == LifecycleState.FAILED) {
                return;
            }
            if (this.state.compareAndSet(current, LifecycleState.STOPPING)) {
                if (this.service != null) {
                    this.service.stopAcceptingMutations();
                    this.plugin.getServer().getServicesManager().unregister(NetworkPointsService.class, this.service);
                }
                if (this.auditTask != null) {
                    this.auditTask.cancel();
                }
                awaitOperations();
                closeRuntime();
                this.state.set(LifecycleState.STOPPED);
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
        BalanceCache cache = this.balanceCache;
        if (cache != null) {
            cache.invalidate(event.getPlayer().getUniqueId());
        }
        if (this.feedback != null) {
            this.feedback.clear(event.getPlayer());
        }
        if (this.identities != null) {
            this.identities.invalidate(event.getPlayer().getUniqueId());
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
        new PointsCommandController(
                this.plugin, this.commandManager, this.service, this.accountStore, this.auditStore, this.identities,
                this.feedback, this.configuration::snapshot, this::state, this::redisState, this::reload).register();
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
        try {
            AtomicReference<LocalizedCatalog<FeedbackAction>> catalog = new AtomicReference<>();
            ConfigSnapshot snapshot = this.configuration.reload(reloadable ->
                    catalog.set(new FeedbackCompiler().compile(reloadable.messages())));
            this.feedback.update(catalog.get());
            this.identities.update(snapshot.reloadable().identity());
            updatePresentation(snapshot);
            if (this.auditTask != null) {
                this.auditTask.cancel();
            }
            scheduleAuditCleanup(snapshot.reloadable().audit());
            this.feedback.send(sender, "reload-success", Map.of(
                    "restart_changes", net.kyori.adventure.text.Component.text(String.join(", ", snapshot.restartRequiredChanges()))));
        } catch (Exception exception) {
            this.plugin.getComponentLogger().warn("NetworkPoints reload rejected.", exception);
            this.feedback.send(sender, "reload-failed", Map.of());
        }
    }

    private void updatePresentation(ConfigSnapshot snapshot) {
        ConfigSnapshot.Reloadable reloadable = snapshot.reloadable();
        AmountParser parser = new AmountParser(java.math.BigDecimal.ZERO,
                snapshot.restartRequired().monetaryPolicy().maximumBalance(),
                snapshot.restartRequired().monetaryPolicy().maximumBalance(), reloadable.amountInput().suffixes());
        List<CompactTier> tiers = reloadable.amountFormat().compactTiers().stream()
                .map(tier -> new CompactTier(tier.threshold(), tier.pattern(), tier.suffix(), tier.display())).toList();
        AmountFormatter formatter = new AmountFormatter(reloadable.amountFormat().groupedPattern(),
                reloadable.amountFormat().groupingSeparator(), reloadable.amountFormat().decimalSeparator(), tiers);
        this.service.updatePresentation(parser, formatter,
                AmountDisplayMode.valueOf(reloadable.amountFormat().defaultMode().name()), reloadable.currency());
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
        if (store == null || this.state.get() == LifecycleState.STOPPING || this.state.get() == LifecycleState.STOPPED) {
            return;
        }
        store.ensureAccount(playerId, name).thenAccept(account -> {
            BalanceCache cache = this.balanceCache;
            if (cache == null || this.state.get() != LifecycleState.RUNNING
                    || this.plugin.getServer().getPlayer(playerId) == null) {
                return;
            }
            var snapshot = cache.publish(account.snapshot());
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
                if (this.state.get() == LifecycleState.RUNNING
                        && this.plugin.getServer().getPlayer(playerId) != null
                        && cache.getIfPresent(playerId).filter(snapshot::equals).isPresent()) {
                    this.plugin.getServer().getPluginManager().callEvent(new PlayerPointsReadyEvent(snapshot));
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
        if (this.expansion != null) {
            this.expansion.unregister();
            this.expansion = null;
        }
        if (this.feedback != null) {
            this.feedback.close();
            this.feedback = null;
        }
        if (this.identities != null) {
            this.identities.close();
            this.identities = null;
        }
        if (this.redisStatus != null) {
            this.redisStatus.close();
            this.redisStatus = null;
        }
        if (this.synchronization != null) {
            this.synchronization.close();
            this.synchronization = null;
        }
        if (this.redis != null) {
            try {
                this.redis.close();
            } catch (RuntimeException exception) {
                this.plugin.getComponentLogger().error("NetworkPoints Redis shutdown failed.", exception);
            }
            this.redis = null;
        }
        closeDatabase();
        if (this.balanceCache != null) {
            this.balanceCache.close();
            this.balanceCache = null;
        }
    }

    private void scheduleAuditCleanup(ConfigSnapshot.Audit audit) {
        long intervalTicks = Math.multiplyExact(audit.cleanupIntervalHours(), 72_000L);
        this.auditTask = this.plugin.getServer().getScheduler().runTaskTimer(
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
            try {
                current.close();
            } catch (RuntimeException exception) {
                this.plugin.getComponentLogger().error("NetworkPoints database shutdown failed.", exception);
            }
        }
    }
}
