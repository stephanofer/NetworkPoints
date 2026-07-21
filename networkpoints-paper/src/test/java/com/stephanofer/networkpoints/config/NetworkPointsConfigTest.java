package com.stephanofer.networkpoints.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPointsConfigTest {
    @TempDir
    Path directory;

    @Test
    void createsEveryDefaultAndPublishesTypedSnapshot() throws Exception {
        NetworkPointsConfig configuration = new NetworkPointsConfig(directory);

        ConfigSnapshot snapshot = configuration.start();

        assertSame(snapshot, configuration.snapshot());
        assertEquals("lobby-1", snapshot.restartRequired().serverId());
        assertEquals(new BigDecimal("999999999999999.00"), snapshot.restartRequired().monetaryPolicy().maximumBalance());
        assertTrue(snapshot.restartRequiredChanges().isEmpty());
        assertEquals(5, snapshot.reloadable().amountFormat().compactTiers().size());
        assertTrue(snapshot.reloadable().messages().get("es").actions().containsKey("pay-sent"));
        assertTrue(Files.exists(directory.resolve("config.yml")));
        assertTrue(Files.exists(directory.resolve("commands.yml")));
        assertTrue(Files.exists(directory.resolve("messages/es.yml")));
        assertTrue(Files.exists(directory.resolve("messages/en.yml")));
    }

    @Test
    void autoUpdatesMissingDefaultsAndPreservesCustomValues() throws Exception {
        NetworkPointsConfig first = new NetworkPointsConfig(directory);
        first.start();
        Path configFile = directory.resolve("config.yml");
        String content = Files.readString(configFile)
                .replace("server-id: lobby-1", "server-id: games-7")
                .replace("  unavailable-value: ''" + System.lineSeparator(), "");
        Files.writeString(configFile, content);

        ConfigSnapshot snapshot = new NetworkPointsConfig(directory).start();

        assertEquals("games-7", snapshot.restartRequired().serverId());
        assertTrue(Files.readString(configFile).contains("unavailable-value"));
    }

    @Test
    void rejectsDuplicateKeys() throws Exception {
        new NetworkPointsConfig(directory).start();
        Path commands = directory.resolve("commands.yml");
        Files.writeString(commands, Files.readString(commands) + System.lineSeparator() + "root: {}" + System.lineSeparator());

        ConfigValidationException exception = assertThrows(ConfigValidationException.class,
                () -> new NetworkPointsConfig(directory).start());

        assertTrue(exception.errors().stream().anyMatch(error -> error.startsWith("commands.yml:")));
    }

    @Test
    void invalidReloadKeepsPreviousSnapshot() throws Exception {
        NetworkPointsConfig configuration = new NetworkPointsConfig(directory);
        ConfigSnapshot original = configuration.start();
        replace(directory.resolve("config.yml"), "grouping-separator: ','", "grouping-separator: '.'");

        ConfigValidationException exception = assertThrows(ConfigValidationException.class, configuration::reload);

        assertSame(original, configuration.snapshot());
        assertTrue(exception.errors().stream().anyMatch(error -> error.contains("config.yml:amount-format.decimal-separator")));
    }

    @Test
    void validReloadChangesOnlyReloadableConfiguration() throws Exception {
        NetworkPointsConfig configuration = new NetworkPointsConfig(directory);
        ConfigSnapshot original = configuration.start();
        replace(directory.resolve("config.yml"), "server-id: lobby-1", "server-id: games-2");
        replace(directory.resolve("config.yml"), "symbol: ✦", "symbol: NP");
        replace(directory.resolve("commands.yml"), "name: points", "name: networkpoints");

        ConfigSnapshot reloaded = configuration.reload();

        assertNotSame(original, reloaded);
        assertSame(original.restartRequired(), reloaded.restartRequired());
        assertEquals("lobby-1", reloaded.restartRequired().serverId());
        assertEquals("points", reloaded.restartRequired().commands().root().name());
        assertEquals("NP", reloaded.reloadable().currency().symbol());
        assertEquals(java.util.List.of("server-id", "commands"), reloaded.restartRequiredChanges());
        assertThrows(UnsupportedOperationException.class, () -> reloaded.restartRequiredChanges().add("database"));
    }

    @Test
    void startupAccumulatesErrorsWithFileAndPath() throws Exception {
        new NetworkPointsConfig(directory).start();
        replaceLine(directory.resolve("config.yml"), "  maximum-balance:", "  maximum-balance: 99999999999999999999999999999.00");
        replaceLine(directory.resolve("config.yml"), "  minimum-amount:", "  minimum-amount: 200.00");
        replaceLine(directory.resolve("config.yml"), "  maximum-amount:", "  maximum-amount: 100.00");
        replace(directory.resolve("commands.yml"), "permission: networkpoints.pay", "permission: INVALID");

        ConfigValidationException exception = assertThrows(ConfigValidationException.class,
                () -> new NetworkPointsConfig(directory).start());

        assertTrue(exception.errors().stream().anyMatch(error -> error.contains("config.yml:currency.maximum-balance")));
        assertTrue(exception.errors().stream().anyMatch(error -> error.contains("config.yml:payments.maximum-amount")));
        assertTrue(exception.errors().stream().anyMatch(error -> error.contains("commands.yml:commands.pay.permission")));
        assertTrue(exception.errors().size() >= 3);
    }

    @Test
    void messagesRemainRawAndMiniMessageIsNotCompiled() throws Exception {
        new NetworkPointsConfig(directory).start();
        Path messages = directory.resolve("messages/en.yml");
        String content = Files.readString(messages).replace("<gray>Your balance: <amount></gray>", "<not-a-real-tag><amount>");
        Files.writeString(messages, content);

        ConfigSnapshot snapshot = new NetworkPointsConfig(directory).start();

        Object message = snapshot.reloadable().messages().get("en").actions().get("balance").getFirst().get("message");
        assertEquals("<not-a-real-tag><amount>", message);
        assertFalse(snapshot.reloadable().messages().get("en").actions().isEmpty());
    }

    @Test
    void validatesStructuralAndOrderingInvariantsTogether() throws Exception {
        new NetworkPointsConfig(directory).start();
        replace(directory.resolve("config.yml"), "server-id: lobby-1", "server-id: INVALID ID");
        replace(directory.resolve("config.yml"), "    k: 1000", "    k: 1000" + System.lineSeparator() + "    K: 2000");
        replaceLine(directory.resolve("config.yml"), "  grouped-pattern:", "  grouped-pattern: \"'\"");
        replaceRegex(directory.resolve("config.yml"), "threshold: 1000(\\R)", "threshold: 2000000$1");
        replaceLine(directory.resolve("config.yml"), "  maximum-size:", "  maximum-size: 0");
        replace(directory.resolve("commands.yml"), "name: balance", "name: point");

        ConfigValidationException exception = assertThrows(ConfigValidationException.class,
                () -> new NetworkPointsConfig(directory).start());

        assertTrue(exception.errors().stream().anyMatch(error -> error.contains("config.yml:server-id")));
        assertTrue(exception.errors().stream().anyMatch(error -> error.contains("config.yml:amount-input.suffixes.K")));
        assertTrue(exception.errors().stream().anyMatch(error -> error.contains("config.yml:amount-format.grouped-pattern")));
        assertTrue(exception.errors().stream().anyMatch(error -> error.contains("thresholds must be strictly increasing")));
        assertTrue(exception.errors().stream().anyMatch(error -> error.contains("config.yml:cache.maximum-size")));
        assertTrue(exception.errors().stream().anyMatch(error -> error.contains("commands.yml:commands.balance.name")));
    }

    @Test
    void malformedSuffixesAndIncompleteTiersAccumulateValidationErrorsWithoutNpe() throws Exception {
        new NetworkPointsConfig(directory).start();
        replace(directory.resolve("config.yml"), "    k: 1000", "    bad_1:");
        replace(directory.resolve("config.yml"), "suffix: K", "suffix:");

        ConfigValidationException exception = assertThrows(ConfigValidationException.class,
                () -> new NetworkPointsConfig(directory).start());

        assertTrue(exception.errors().stream().anyMatch(error -> error.contains("amount-input.suffixes.bad_1")));
        assertTrue(exception.errors().stream().anyMatch(error -> error.contains("amount-format.compact[0].suffix")));
    }

    @Test
    void invalidYamlTypesProduceValidationErrorsInsteadOfNullPointerExceptions() throws Exception {
        new NetworkPointsConfig(directory).start();
        replace(directory.resolve("config.yml"), "    k: 1000", "    k: invalid");
        replace(directory.resolve("config.yml"), "threshold: 1000", "threshold: invalid");

        ConfigValidationException exception = assertThrows(ConfigValidationException.class,
                () -> new NetworkPointsConfig(directory).start());

        assertTrue(exception.errors().stream().anyMatch(error -> error.contains("amount-input.suffixes.k")));
        assertTrue(exception.errors().stream().anyMatch(error -> error.contains("amount-format.compact[0].threshold")));
    }

    @Test
    void reloadReportsMaximumBalanceAsRestartRequiredAndKeepsTheActiveValue() throws Exception {
        NetworkPointsConfig configuration = new NetworkPointsConfig(directory);
        ConfigSnapshot original = configuration.start();
        replaceLine(directory.resolve("config.yml"), "  maximum-balance:",
                "  maximum-balance: 9999999999999999.00");

        ConfigSnapshot reloaded = configuration.reload();

        assertEquals(original.restartRequired().monetaryPolicy(), reloaded.restartRequired().monetaryPolicy());
        assertEquals(java.util.List.of("currency.maximum-balance"), reloaded.restartRequiredChanges());
    }

    @Test
    void infrastructureRecordsHidePasswordsInToString() throws Exception {
        ConfigSnapshot snapshot = new NetworkPointsConfig(directory).start();

        assertFalse(snapshot.restartRequired().database().toString().contains("change-me"));
        assertFalse(snapshot.restartRequired().redis().toString().contains("change-me"));
        assertTrue(snapshot.restartRequired().database().toString().contains("password=<hidden>"));
        assertTrue(snapshot.restartRequired().redis().toString().contains("password=<hidden>"));
    }

    private static void replace(Path file, String expected, String replacement) throws IOException {
        String content = Files.readString(file);
        assertTrue(content.contains(expected), () -> "Expected text not found in " + file + ": " + expected);
        Files.writeString(file, content.replace(expected, replacement));
    }

    private static void replaceLine(Path file, String prefix, String replacement) throws IOException {
        String content = Files.readString(file);
        String updated = content.lines()
                .map(line -> line.startsWith(prefix) ? replacement : line)
                .collect(java.util.stream.Collectors.joining(System.lineSeparator(), "", System.lineSeparator()));
        assertFalse(content.equals(updated), () -> "Expected line not found in " + file + ": " + prefix);
        Files.writeString(file, updated);
    }

    private static void replaceRegex(Path file, String pattern, String replacement) throws IOException {
        String content = Files.readString(file);
        String updated = content.replaceFirst(pattern, replacement);
        assertFalse(content.equals(updated), () -> "Expected pattern not found in " + file + ": " + pattern);
        Files.writeString(file, updated);
    }
}
