package com.stephanofer.networkpoints.feedback;

import com.stephanofer.networkpoints.config.ConfigSnapshot;
import com.stephanofer.networkpoints.localization.LocalizedCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.MiniMessage;

public final class FeedbackCompiler {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    public LocalizedCatalog<FeedbackAction> compile(Map<String, ConfigSnapshot.Messages> messages) {
        Map<String, Map<String, List<FeedbackAction>>> catalogs = new LinkedHashMap<>();
        messages.forEach((language, catalog) -> catalogs.put(language, compileCatalog(language, catalog)));
        return new LocalizedCatalog<>("en", catalogs);
    }

    private static Map<String, List<FeedbackAction>> compileCatalog(String language, ConfigSnapshot.Messages messages) {
        Map<String, List<FeedbackAction>> result = new LinkedHashMap<>();
        messages.actions().forEach((key, rawActions) -> {
            List<FeedbackAction> actions = new ArrayList<>(rawActions.size());
            for (int index = 0; index < rawActions.size(); index++) {
                actions.add(compileAction(rawActions.get(index), language + ":" + key + "[" + index + "]"));
            }
            result.put(key, List.copyOf(actions));
        });
        return Map.copyOf(result);
    }

    private static FeedbackAction compileAction(Map<String, Object> raw, String path) {
        String type = string(raw, "type", path).toUpperCase(Locale.ROOT);
        return switch (type) {
            case "CHAT" -> new FeedbackAction.Chat(message(raw, "message", path));
            case "ACTION_BAR" -> new FeedbackAction.ActionBar(message(raw, "message", path));
            case "TITLE" -> new FeedbackAction.Title(
                    message(raw, "title", path), message(raw, "subtitle", path),
                    nonNegativeLong(raw, "fade-in-millis", path), positiveLong(raw, "stay-millis", path),
                    nonNegativeLong(raw, "fade-out-millis", path));
            case "SOUND" -> new FeedbackAction.SoundEffect(
                    key(raw, "sound", path), enumValue(raw, "source", Sound.Source.class, path),
                    positiveFloat(raw, "volume", path), positiveFloat(raw, "pitch", path));
            case "BOSS_BAR" -> new FeedbackAction.BossBarEffect(
                    message(raw, "message", path), enumValue(raw, "color", BossBar.Color.class, path),
                    enumValue(raw, "overlay", BossBar.Overlay.class, path), progress(raw, path),
                    positiveLong(raw, "duration-millis", path));
            default -> throw new IllegalArgumentException(path + ": unknown feedback type " + type);
        };
    }

    private static String message(Map<String, Object> raw, String key, String path) {
        String value = string(raw, key, path);
        MINI_MESSAGE.deserialize(value);
        return value;
    }

    private static String string(Map<String, Object> raw, String key, String path) {
        Object value = raw.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(path + ": " + key + " must be a non-blank string");
        }
        return text;
    }

    private static Key key(Map<String, Object> raw, String key, String path) {
        try {
            return Key.key(string(raw, key, path));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(path + ": invalid sound key", exception);
        }
    }

    private static long positiveLong(Map<String, Object> raw, String key, String path) {
        long value = number(raw, key, path).longValue();
        if (value <= 0) {
            throw new IllegalArgumentException(path + ": " + key + " must be positive");
        }
        return value;
    }

    private static long nonNegativeLong(Map<String, Object> raw, String key, String path) {
        long value = number(raw, key, path).longValue();
        if (value < 0) {
            throw new IllegalArgumentException(path + ": " + key + " must not be negative");
        }
        return value;
    }

    private static float positiveFloat(Map<String, Object> raw, String key, String path) {
        float value = number(raw, key, path).floatValue();
        if (!Float.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(path + ": " + key + " must be a finite positive number");
        }
        return value;
    }

    private static float progress(Map<String, Object> raw, String path) {
        float value = number(raw, "progress", path).floatValue();
        if (!Float.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(path + ": progress must be between 0 and 1");
        }
        return value;
    }

    private static Number number(Map<String, Object> raw, String key, String path) {
        Object value = raw.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(path + ": " + key + " must be a number");
        }
        return number;
    }

    private static <E extends Enum<E>> E enumValue(Map<String, Object> raw, String key, Class<E> type, String path) {
        try {
            return Enum.valueOf(type, string(raw, key, path).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(path + ": invalid " + key, exception);
        }
    }
}
