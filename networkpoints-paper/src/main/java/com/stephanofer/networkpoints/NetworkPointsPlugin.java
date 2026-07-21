package com.stephanofer.networkpoints;

import com.stephanofer.networkpoints.lifecycle.NetworkPointsLifecycle;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.PaperCommandManager;

public final class NetworkPointsPlugin extends JavaPlugin {

    private NetworkPointsLifecycle lifecycle;

    @Override
    public void onEnable() {
        PaperCommandManager<CommandSourceStack> commandManager = PaperCommandManager.builder()
                .executionCoordinator(ExecutionCoordinator.simpleCoordinator())
                .buildOnEnable(this);
        NetworkPointsLifecycle created = new NetworkPointsLifecycle(this, commandManager);
        this.lifecycle = created;
        created.start();
    }

    @Override
    public void onDisable() {
        NetworkPointsLifecycle current = this.lifecycle;
        if (current != null) {
            current.stop();
        }
    }
}
