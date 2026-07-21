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
import java.time.Clock;
import java.time.Duration;
import java.util.List;
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

public final class NetworkPointsLifecycle implements Listener {

    private final JavaPlugin plugin;
    private final NetworkPointsConfig configuration;
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

    public NetworkPointsLifecycle(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
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
        preload(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        BalanceCache cache = this.balanceCache;
        if (cache != null) {
            cache.invalidate(event.getPlayer().getUniqueId());
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
                reloadable.payments().minimumAmount(),
                reloadable.payments().maximumAmount(),
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
                engine, this.balanceCache, postCommit, parser, formatter, defaultMode);
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
