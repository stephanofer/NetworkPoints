package com.stephanofer.networkpoints.feedback;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;

public sealed interface FeedbackAction permits FeedbackAction.Chat, FeedbackAction.ActionBar, FeedbackAction.Title,
        FeedbackAction.SoundEffect, FeedbackAction.BossBarEffect {

    record Chat(String message) implements FeedbackAction {
    }

    record ActionBar(String message) implements FeedbackAction {
    }

    record Title(String title, String subtitle, long fadeInMillis, long stayMillis, long fadeOutMillis)
            implements FeedbackAction {
    }

    record SoundEffect(Key sound, net.kyori.adventure.sound.Sound.Source source, float volume, float pitch)
            implements FeedbackAction {
    }

    record BossBarEffect(String message, BossBar.Color color, BossBar.Overlay overlay, float progress,
                         long durationMillis) implements FeedbackAction {
    }
}
