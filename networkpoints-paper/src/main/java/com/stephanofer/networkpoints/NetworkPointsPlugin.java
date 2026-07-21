package com.stephanofer.networkpoints;

import com.stephanofer.networkpoints.lifecycle.NetworkPointsLifecycle;
import org.bukkit.plugin.java.JavaPlugin;

public final class NetworkPointsPlugin extends JavaPlugin {

    private NetworkPointsLifecycle lifecycle;

    @Override
    public void onEnable() {
        NetworkPointsLifecycle created = new NetworkPointsLifecycle(this);
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
