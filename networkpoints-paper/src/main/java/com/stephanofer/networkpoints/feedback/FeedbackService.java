package com.stephanofer.networkpoints.feedback;

import com.stephanofer.networkpoints.localization.LocalizedCatalog;
import com.stephanofer.networkplayersettings.settings.api.PlayerSettingsService;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class FeedbackService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final PlayerSettingsService settings;
    private final AtomicReference<LocalizedCatalog<FeedbackAction>> catalog;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, Set<BossBar>> bossBars = new ConcurrentHashMap<>();
    private final Map<UUID, ConcurrentLinkedQueue<PendingFeedback>> pending = new ConcurrentHashMap<>();

    public FeedbackService(JavaPlugin plugin, PlayerSettingsService settings,
                           LocalizedCatalog<FeedbackAction> initialCatalog) {
        this.plugin = plugin;
        this.settings = settings;
        this.catalog = new AtomicReference<>(initialCatalog);
    }

    public void update(LocalizedCatalog<FeedbackAction> replacement) {
        clearBossBars();
        this.catalog.set(replacement);
    }

    public void send(CommandSender audience, String key, Map<String, Component> values) {
        if (audience instanceof Player player && !this.settings.isReady(player.getUniqueId())) {
            ConcurrentLinkedQueue<PendingFeedback> queue = this.pending.computeIfAbsent(
                    player.getUniqueId(), ignored -> new ConcurrentLinkedQueue<>());
            if (queue.size() < 32) {
                queue.add(new PendingFeedback(key, Map.copyOf(values)));
            }
            return;
        }
        String language = audience instanceof Player player ? this.settings.resolvedLanguage(player).code() : "en";
        TagResolver resolver = TagResolver.resolver(values.entrySet().stream()
                .map(entry -> Placeholder.component(entry.getKey(), entry.getValue())).toArray(TagResolver[]::new));
        for (FeedbackAction action : this.catalog.get().actions(language, key)) {
            dispatch(audience, action, resolver);
        }
    }

    public void clear(Player player) {
        this.pending.remove(player.getUniqueId());
        Set<BossBar> bars = this.bossBars.remove(player.getUniqueId());
        if (bars != null) {
            bars.forEach(player::hideBossBar);
        }
    }

    public void ready(Player player) {
        ConcurrentLinkedQueue<PendingFeedback> queue = this.pending.remove(player.getUniqueId());
        if (queue == null) {
            return;
        }
        PendingFeedback feedback;
        while ((feedback = queue.poll()) != null) {
            send(player, feedback.key(), feedback.values());
        }
    }

    private void dispatch(CommandSender audience, FeedbackAction action, TagResolver resolver) {
        switch (action) {
            case FeedbackAction.Chat chat -> audience.sendMessage(render(chat.message(), resolver));
            case FeedbackAction.ActionBar bar -> audience.sendActionBar(render(bar.message(), resolver));
            case FeedbackAction.Title title -> audience.showTitle(Title.title(
                    render(title.title(), resolver), render(title.subtitle(), resolver), Title.Times.times(
                            Duration.ofMillis(title.fadeInMillis()), Duration.ofMillis(title.stayMillis()),
                            Duration.ofMillis(title.fadeOutMillis()))));
            case FeedbackAction.SoundEffect sound -> audience.playSound(Sound.sound(
                    sound.sound(), sound.source(), sound.volume(), sound.pitch()));
            case FeedbackAction.BossBarEffect effect -> showBossBar(audience, effect, resolver);
        }
    }

    private void showBossBar(CommandSender audience, FeedbackAction.BossBarEffect effect, TagResolver resolver) {
        if (!(audience instanceof Player player)) {
            return;
        }
        BossBar bar = BossBar.bossBar(render(effect.message(), resolver), effect.progress(), effect.color(), effect.overlay());
        this.bossBars.computeIfAbsent(player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet()).add(bar);
        player.showBossBar(bar);
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            player.hideBossBar(bar);
            Set<BossBar> bars = this.bossBars.get(player.getUniqueId());
            if (bars != null && bars.remove(bar) && bars.isEmpty()) {
                this.bossBars.remove(player.getUniqueId(), bars);
            }
        }, Math.max(1L, (effect.durationMillis() + 49L) / 50L));
    }

    private Component render(String input, TagResolver resolver) {
        return this.miniMessage.deserialize(input, resolver);
    }

    private void clearBossBars() {
        this.plugin.getServer().getOnlinePlayers().forEach(this::clear);
        this.bossBars.clear();
        this.pending.clear();
    }

    @Override
    public void close() {
        clearBossBars();
    }

    private record PendingFeedback(String key, Map<String, Component> values) {
    }
}
