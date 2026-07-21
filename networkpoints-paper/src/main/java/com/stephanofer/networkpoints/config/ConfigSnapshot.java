package com.stephanofer.networkpoints.config;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ConfigSnapshot(
        RestartRequired restartRequired,
        Reloadable reloadable,
        List<String> restartRequiredChanges
) {
    public ConfigSnapshot {
        restartRequiredChanges = List.copyOf(restartRequiredChanges);
    }

    public record RestartRequired(
            String serverId,
            Database database,
            Redis redis,
            Commands commands,
            Integrations integrations,
            MonetaryPolicy monetaryPolicy
    ) {
    }

    public record Reloadable(
            Currency currency,
            AmountInput amountInput,
            AmountFormat amountFormat,
            Payments payments,
            Cache cache,
            Audit audit,
            Identity identity,
            Placeholder placeholderApi,
            Map<String, Messages> messages,
            Map<String, PaymentDialogText> paymentDialogs
    ) {
        public Reloadable {
            messages = Map.copyOf(messages);
            paymentDialogs = Map.copyOf(paymentDialogs);
        }
    }

    public record Database(
            String host,
            int port,
            String name,
            String tablePrefix,
            String username,
            String password,
            int maximumPoolSize,
            int minimumIdle,
            long connectionTimeoutMillis,
            long validationTimeoutMillis,
            long shutdownTimeoutMillis
    ) {
        @Override
        public String toString() {
            return "Database[host=" + host + ", port=" + port + ", name=" + name + ", tablePrefix="
                    + tablePrefix + ", username=" + username + ", password=<hidden>, maximumPoolSize="
                    + maximumPoolSize + ", minimumIdle=" + minimumIdle + ", connectionTimeoutMillis="
                    + connectionTimeoutMillis + ", validationTimeoutMillis=" + validationTimeoutMillis
                    + ", shutdownTimeoutMillis=" + shutdownTimeoutMillis + "]";
        }
    }

    public record Redis(
            String host,
            int port,
            int database,
            String username,
            String password,
            boolean ssl,
            boolean verifyPeer,
            String environment,
            String keyPrefix,
            long commandTimeoutMillis,
            long connectTimeoutMillis,
            long shutdownTimeoutMillis,
            boolean autoReconnect
    ) {
        @Override
        public String toString() {
            return "Redis[host=" + host + ", port=" + port + ", database=" + database + ", username=" + username
                    + ", password=<hidden>, ssl=" + ssl + ", verifyPeer=" + verifyPeer + ", environment="
                    + environment + ", keyPrefix=" + keyPrefix + ", commandTimeoutMillis=" + commandTimeoutMillis
                    + ", connectTimeoutMillis=" + connectTimeoutMillis + ", shutdownTimeoutMillis="
                    + shutdownTimeoutMillis + ", autoReconnect=" + autoReconnect + "]";
        }
    }

    public record Integrations(
            boolean networkBoosters,
            boolean luckPerms,
            boolean networkPlayerSettings,
            boolean placeholderApi
    ) {
    }

    public record Currency(
            String singularName,
            String pluralName,
            String symbol,
            String displayFormat
    ) {
    }

    public record MonetaryPolicy(BigDecimal maximumBalance) {
    }

    public record AmountInput(Map<String, BigDecimal> suffixes) {
        public AmountInput {
            suffixes = Collections.unmodifiableMap(new LinkedHashMap<>(suffixes));
        }
    }

    public enum DisplayMode {
        RAW,
        GROUPED,
        COMPACT
    }

    public record AmountFormat(
            DisplayMode defaultMode,
            String groupedPattern,
            char groupingSeparator,
            char decimalSeparator,
            List<CompactTier> compactTiers
    ) {
        public AmountFormat {
            compactTiers = List.copyOf(compactTiers);
        }
    }

    public record CompactTier(BigDecimal threshold, String pattern, String suffix, String display) {
    }

    public record Payments(
            boolean enabled,
            boolean allowOfflineRecipients,
            BigDecimal minimumAmount,
            BigDecimal maximumAmount,
            long cooldownMillis,
            Confirmation confirmation
    ) {
    }

    public record Confirmation(boolean enabled, BigDecimal minimumAmount, long expiresAfterSeconds) {
    }

    public record Cache(long maximumSize, long refreshAfterWriteSeconds, long expireAfterAccessMinutes) {
    }

    public record Audit(
            int retentionDays,
            int cleanupIntervalHours,
            int cleanupBatchSize,
            int commandPageSize,
            int maximumCommandPage
    ) {
    }

    public enum PrefixFormat {
        MINIMESSAGE,
        LEGACY_AMPERSAND
    }

    public record Identity(
            String format,
            String prefixSuffix,
            String flagPrefix,
            PrefixFormat luckPermsPrefixFormat
    ) {
    }

    public record Placeholder(String unavailableValue) {
    }

    public record Commands(Command root, Map<String, Command> entries) {
        public Commands {
            entries = Map.copyOf(entries);
        }
    }

    public record Command(
            String name,
            List<String> aliases,
            String permission,
            boolean enabled,
            String description
    ) {
        public Command {
            aliases = List.copyOf(aliases);
        }
    }

    public record Messages(Map<String, List<Map<String, Object>>> actions) {
        public Messages {
            actions = actions.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    entry -> entry.getValue().stream().map(Messages::immutableAction).toList()
            ));
        }

        private static Map<String, Object> immutableAction(Map<String, Object> action) {
            Map<String, Object> copy = new LinkedHashMap<>();
            action.forEach((key, value) -> copy.put(key, immutableValue(value)));
            return Collections.unmodifiableMap(copy);
        }

        private static Object immutableValue(Object value) {
            if (value instanceof Map<?, ?> map) {
                Map<Object, Object> copy = new LinkedHashMap<>();
                map.forEach((key, nested) -> copy.put(key, immutableValue(nested)));
                return Collections.unmodifiableMap(copy);
            }
            if (value instanceof List<?> list) {
                return Collections.unmodifiableList(list.stream().map(Messages::immutableValue).toList());
            }
            return value;
        }
    }

    public record PaymentDialogText(
            String title,
            String body,
            String confirmLabel,
            String confirmTooltip,
            String cancelLabel,
            String cancelTooltip
    ) {
    }
}
