package com.stephanofer.networkpoints.feedback;

import com.stephanofer.networkpoints.config.ConfigSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeedbackCompilerTest {
    @Test
    void compilesEverySupportedActionAndFallsBackToEnglish() {
        ConfigSnapshot.Messages english = new ConfigSnapshot.Messages(Map.of("sample", List.of(
                Map.of("type", "CHAT", "message", "<green>Hello"),
                Map.of("type", "ACTION_BAR", "message", "Ready"),
                Map.of("type", "TITLE", "title", "Title", "subtitle", "Sub", "fade-in-millis", 0,
                        "stay-millis", 100, "fade-out-millis", 0),
                Map.of("type", "SOUND", "sound", "minecraft:block.note_block.pling", "source", "MASTER",
                        "volume", 1.0, "pitch", 1.0),
                Map.of("type", "BOSS_BAR", "message", "Boss", "color", "GREEN", "overlay", "PROGRESS",
                        "progress", 1.0, "duration-millis", 1000))));
        ConfigSnapshot.Messages spanish = new ConfigSnapshot.Messages(Map.of());

        var catalog = new FeedbackCompiler().compile(Map.of("en", english, "es", spanish));

        assertEquals(5, catalog.actions("es", "sample").size());
        assertEquals(5, catalog.actions("unsupported", "sample").size());
    }

    @Test
    void rejectsUnknownTypesAndInvalidRanges() {
        ConfigSnapshot.Messages unknown = new ConfigSnapshot.Messages(Map.of(
                "sample", List.of(Map.of("type", "COMMAND", "message", "bad"))));
        ConfigSnapshot.Messages progress = new ConfigSnapshot.Messages(Map.of(
                "sample", List.of(Map.of("type", "BOSS_BAR", "message", "Boss", "color", "GREEN",
                        "overlay", "PROGRESS", "progress", 1.1, "duration-millis", 1000))));

        assertThrows(IllegalArgumentException.class,
                () -> new FeedbackCompiler().compile(Map.of("en", unknown)));
        assertThrows(IllegalArgumentException.class,
                () -> new FeedbackCompiler().compile(Map.of("en", progress)));
    }
}
