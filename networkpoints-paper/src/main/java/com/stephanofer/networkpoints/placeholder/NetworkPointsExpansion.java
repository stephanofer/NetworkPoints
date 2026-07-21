package com.stephanofer.networkpoints.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class NetworkPointsExpansion extends PlaceholderExpansion {
    private final JavaPlugin plugin;
    private final PointsPlaceholderRenderer renderer;

    public NetworkPointsExpansion(JavaPlugin plugin, PointsPlaceholderRenderer renderer) {
        this.plugin = plugin;
        this.renderer = renderer;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "networkpoints";
    }

    @Override
    public @NotNull String getAuthor() {
        return "stephanofer";
    }

    @Override
    public @NotNull String getVersion() {
        return this.plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        return this.renderer.render(player == null ? null : player.getUniqueId(), params);
    }
}
