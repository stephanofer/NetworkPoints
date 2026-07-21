package com.stephanofer.networkpoints.identity;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stephanofer.networkpoints.account.AccountRecord;
import com.stephanofer.networkpoints.account.AccountStore;
import com.stephanofer.networkpoints.config.ConfigSnapshot;
import com.stephanofer.networkplayersettings.assets.api.CountryFlagService;
import com.stephanofer.networkplayersettings.settings.api.NickStyleRenderRequest;
import com.stephanofer.networkplayersettings.settings.api.PlayerStyleService;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class PlayerIdentityService implements AutoCloseable {
    private final AccountStore accounts;
    private final PlayerStyleService styles;
    private final CountryFlagService flags;
    private final LuckPerms luckPerms;
    private final Cache<UUID, CompletableFuture<Component>> cache;
    private final EventSubscription<UserDataRecalculateEvent> luckPermsSubscription;
    private final IdentityComposer composer = new IdentityComposer();
    private volatile ConfigSnapshot.Identity config;

    public PlayerIdentityService(Plugin plugin, AccountStore accounts, PlayerStyleService styles,
                                 CountryFlagService flags, LuckPerms luckPerms,
                                 ConfigSnapshot.Identity config, Duration expiration) {
        this.accounts = accounts;
        this.styles = styles;
        this.flags = flags;
        this.luckPerms = luckPerms;
        this.config = config;
        this.cache = Caffeine.newBuilder().expireAfterAccess(expiration).maximumSize(100_000).build();
        this.luckPermsSubscription = luckPerms.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class,
                event -> invalidate(event.getUser().getUniqueId()));
    }

    public Component online(Player player) {
        return compose(prefixOnline(player), this.styles.formattedNick(player), this.flags.flag(player.getUniqueId()));
    }

    public CompletableFuture<Component> offline(UUID playerId) {
        CompletableFuture<Component> identity = this.cache.get(playerId, this::loadOffline);
        identity.whenComplete((result, failure) -> {
            if (failure != null) {
                this.cache.asMap().remove(playerId, identity);
            }
        });
        return identity;
    }

    public void invalidate(UUID playerId) {
        this.cache.invalidate(playerId);
    }

    public void update(ConfigSnapshot.Identity config) {
        this.config = config;
        this.cache.invalidateAll();
    }

    private CompletableFuture<Component> loadOffline(UUID playerId) {
        return this.accounts.find(playerId).thenCompose(account -> account
                .map(value -> renderOffline(playerId, value))
                .orElseGet(() -> CompletableFuture.failedFuture(
                        new NoSuchElementException("Points account does not exist"))));
    }

    private CompletableFuture<Component> renderOffline(UUID playerId, AccountRecord account) {
        return this.luckPerms.getUserManager().loadUser(playerId).thenCompose(user -> {
            QueryOptions queryOptions = this.luckPerms.getContextManager().getQueryOptions(user)
                    .orElse(this.luckPerms.getContextManager().getStaticQueryOptions());
            Component prefix = parsePrefix(user.getCachedData().getMetaData(queryOptions).getPrefix());
            var permissions = user.getCachedData().getPermissionData(queryOptions);
            CompletableFuture<Component> nick = this.styles.formattedNick(new NickStyleRenderRequest(
                    playerId, account.lastKnownName(), permission -> permissions.checkPermission(permission).asBoolean()));
            CompletableFuture<Component> flag = this.flags.flagAsync(playerId);
            return nick.thenCombine(flag, (renderedNick, renderedFlag) -> compose(prefix, renderedNick, renderedFlag));
        });
    }

    private Component prefixOnline(Player player) {
        return parsePrefix(this.luckPerms.getPlayerAdapter(Player.class).getMetaData(player).getPrefix());
    }

    private Component parsePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return Component.empty();
        }
        try {
            return this.config.luckPermsPrefixFormat() == ConfigSnapshot.PrefixFormat.MINIMESSAGE
                    ? MiniMessage.miniMessage().deserialize(prefix)
                    : LegacyComponentSerializer.legacyAmpersand().deserialize(prefix);
        } catch (RuntimeException exception) {
            return Component.empty();
        }
    }

    private Component compose(Component prefix, Component nick, Component flag) {
        return this.composer.compose(this.config, prefix, nick, flag);
    }

    @Override
    public void close() {
        this.luckPermsSubscription.close();
        this.cache.invalidateAll();
    }
}
