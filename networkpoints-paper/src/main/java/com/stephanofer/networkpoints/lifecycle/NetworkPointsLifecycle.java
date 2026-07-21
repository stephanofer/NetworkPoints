package com.stephanofer.networkpoints.lifecycle;

import com.hera.craftkit.database.Database;
import com.stephanofer.networkpoints.account.AccountRepository;
import com.stephanofer.networkpoints.account.AccountStore;
import com.stephanofer.networkpoints.account.EconomicEngine;
import com.stephanofer.networkpoints.amount.AmountFormatter;
import com.stephanofer.networkpoints.amount.AmountParser;
import com.stephanofer.networkpoints.amount.CompactTier;
import com.stephanofer.networkpoints.api.NetworkPointsService;
import com.stephanofer.networkpoints.api.amount.AmountDisplayMode;
import com.stephanofer.networkpoints.config.ConfigSnapshot;
import com.stephanofer.networkpoints.config.NetworkPointsConfig;
import com.stephanofer.networkpoints.persistence.AuditStore;
import com.stephanofer.networkpoints.persistence.DatabaseFactory;
import com.stephanofer.networkpoints.persistence.TransactionRepository;
import com.stephanofer.networkpoints.service.DurableNetworkPointsService;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
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
    private DurableNetworkPointsService service;
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
            this.plugin.getServer().getOnlinePlayers().forEach(player -> ensureAccount(player.getUniqueId(), player.getName()));
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
            closeDatabase();
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
                closeDatabase();
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
        ensureAccount(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    private void startDurableCore(ConfigSnapshot snapshot) {
        this.database = DatabaseFactory.create(
                snapshot.restartRequired().database(), this.plugin.getClass().getClassLoader());
        this.database.migrate().join();

        AccountRepository accounts = new AccountRepository();
        TransactionRepository transactions = new TransactionRepository();
        this.accountStore = new AccountStore(this.database, accounts);
        this.auditStore = new AuditStore(this.database, transactions, Clock.systemUTC());
        EconomicEngine engine = new EconomicEngine(
                this.database,
                accounts,
                transactions,
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
        this.service = new DurableNetworkPointsService(this.accountStore, engine, parser, formatter, defaultMode);
    }

    private void ensureAccount(java.util.UUID playerId, String name) {
        AccountStore store = this.accountStore;
        if (store == null || this.state.get() == LifecycleState.STOPPING || this.state.get() == LifecycleState.STOPPED) {
            return;
        }
        store.ensureAccount(playerId, name).exceptionally(failure -> {
            this.plugin.getComponentLogger().error("Could not create or update points account for {}.", name, failure);
            return null;
        });
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
