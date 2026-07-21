package com.stephanofer.networkpoints;

import com.stephanofer.networkpoints.lifecycle.NetworkPointsLifecycle;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.paper.PaperCommandManager;

public final class NetworkPointsPlugin extends JavaPlugin {

    private NetworkPointsLifecycle lifecycle;
    private final PaperCommandManager.Bootstrapped<CommandSourceStack> commandManager;

    public NetworkPointsPlugin(PaperCommandManager.Bootstrapped<CommandSourceStack> commandManager) {
        this.commandManager = commandManager;
    }

    @Override
    public void onEnable() {
        this.commandManager.onEnable();
        NetworkPointsLifecycle created = new NetworkPointsLifecycle(this, this.commandManager);
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
