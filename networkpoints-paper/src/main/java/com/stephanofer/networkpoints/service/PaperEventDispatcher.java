package com.stephanofer.networkpoints.service;

import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperEventDispatcher implements PointsEventDispatcher {
    private final JavaPlugin plugin;

    public PaperEventDispatcher(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void dispatch(Event event) {
        Objects.requireNonNull(event, "event");
        Runnable call = () -> this.plugin.getServer().getPluginManager().callEvent(event);
        if (Bukkit.isPrimaryThread()) {
            call.run();
        } else {
            this.plugin.getServer().getScheduler().runTask(this.plugin, call);
        }
    }
}
