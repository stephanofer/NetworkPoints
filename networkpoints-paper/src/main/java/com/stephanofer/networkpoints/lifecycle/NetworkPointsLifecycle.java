package com.stephanofer.networkpoints.lifecycle;

import com.stephanofer.networkpoints.config.ConfigSnapshot;
import com.stephanofer.networkpoints.config.NetworkPointsConfig;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.plugin.java.JavaPlugin;

public final class NetworkPointsLifecycle {

    private final JavaPlugin plugin;
    private final NetworkPointsConfig configuration;
    private final AtomicReference<LifecycleState> state = new AtomicReference<>(LifecycleState.NEW);

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
            if (!this.state.compareAndSet(LifecycleState.STARTING, LifecycleState.RUNNING)) {
                return;
            }
            this.plugin.getComponentLogger().info(
                "NetworkPoints foundations started for server {}.",
                snapshot.restartRequired().serverId()
            );
        } catch (Exception exception) {
            if (!this.state.compareAndSet(LifecycleState.STARTING, LifecycleState.FAILED)) {
                return;
            }
            this.plugin.getComponentLogger().error("NetworkPoints could not start.", exception);
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
}
