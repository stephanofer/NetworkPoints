package com.stephanofer.networkpoints.config;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.block.Block;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import dev.dejvokep.boostedyaml.dvs.versioning.BasicVersioning;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

final class ConfigLoader {
    private static final int CONFIG_VERSION = 1;
    private static final BigDecimal DECIMAL_30_2_MAX = new BigDecimal("9999999999999999999999999999.99");
    private static final Pattern COMPONENT_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern COMMAND_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final Pattern PERMISSION = Pattern.compile("[a-z0-9][a-z0-9._-]*(?:\\.[a-z0-9][a-z0-9._-]*)+");
    private static final Pattern CRAFTKIT_COMPONENT = Pattern.compile("[A-Za-z0-9._:-]{1,256}");
    private static final List<String> COMMAND_KEYS = List.of("balance", "pay", "give", "take", "set", "reset", "history", "reload", "status");

    private final Path dataDirectory;

    ConfigLoader(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    ConfigSnapshot loadCandidate() throws IOException, ConfigValidationException {
        List<String> errors = new ArrayList<>();
        YamlDocument config = load("config.yml", errors);
        YamlDocument commands = load("commands.yml", errors);
        YamlDocument spanish = load("messages/es.yml", errors);
        YamlDocument english = load("messages/en.yml", errors);
        if (config == null || commands == null || spanish == null || english == null) {
            throw new ConfigValidationException(errors);
        }

        Reader c = new Reader("config.yml", config, errors);
        Reader cmd = new Reader("commands.yml", commands, errors);
        validateVersion(c);
        validateVersion(cmd);

        String serverId = c.string("server-id");
        ConfigSnapshot.Database database = readDatabase(c);
        ConfigSnapshot.Redis redis = readRedis(c);
        ConfigSnapshot.Integrations integrations = readIntegrations(c);
        ConfigSnapshot.MonetaryPolicy monetaryPolicy = readMonetaryPolicy(c);
        ConfigSnapshot.Currency currency = readCurrency(c);
        ConfigSnapshot.AmountInput amountInput = readAmountInput(c);
        ConfigSnapshot.AmountFormat amountFormat = readAmountFormat(c);
        ConfigSnapshot.Payments payments = readPayments(c);
        ConfigSnapshot.Cache cache = readCache(c);
        ConfigSnapshot.Audit audit = readAudit(c);
        ConfigSnapshot.Identity identity = readIdentity(c);
        ConfigSnapshot.Placeholder placeholder = new ConfigSnapshot.Placeholder(c.string("placeholder-api.unavailable-value"));
        ConfigSnapshot.Commands commandConfig = readCommands(cmd);
        ConfigSnapshot.Messages es = readMessages("messages/es.yml", spanish, errors);
        ConfigSnapshot.Messages en = readMessages("messages/en.yml", english, errors);

        c.require(serverId != null && COMPONENT_ID.matcher(serverId).matches(), "server-id", "must be a lowercase component ID (maximum 64 characters)");
        validateCrossSection(c, monetaryPolicy, amountInput, amountFormat, payments, cache, audit, identity);
        if (!errors.isEmpty()) {
            throw new ConfigValidationException(errors);
        }

        return new ConfigSnapshot(
                new ConfigSnapshot.RestartRequired(serverId, database, redis, commandConfig, integrations, monetaryPolicy),
                new ConfigSnapshot.Reloadable(currency, amountInput, amountFormat, payments, cache, audit, identity,
                        placeholder, Map.of("es", es, "en", en)),
                List.of()
        );
    }

    private YamlDocument load(String relativePath, List<String> errors) throws IOException {
        Path target = dataDirectory.resolve(relativePath);
        Files.createDirectories(target.getParent());
        InputStream defaults = ConfigLoader.class.getClassLoader().getResourceAsStream(relativePath);
        if (defaults == null) {
            throw new IOException("Missing bundled configuration resource: " + relativePath);
        }
        LoaderSettings loader = LoaderSettings.builder()
                .setCreateFileIfAbsent(true)
                .setAutoUpdate(true)
                .setAllowDuplicateKeys(false)
                .setErrorLabel(relativePath)
                .build();
        UpdaterSettings updater = UpdaterSettings.builder()
                .setVersioning(new BasicVersioning("config-version"))
                .setKeepAll(true)
                .setAutoSave(true)
                .build();
        try (defaults) {
            // A fresh document is mandatory: BoostedYAML clears and mutates a document before reload can fail.
            return YamlDocument.create(target.toFile(), defaults, loader, updater);
        } catch (RuntimeException | IOException exception) {
            errors.add(relativePath + ": " + rootMessage(exception));
            return null;
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static void validateVersion(Reader reader) {
        Integer version = reader.integer("config-version");
        reader.require(version != null && version == CONFIG_VERSION, "config-version", "must be " + CONFIG_VERSION);
    }

    private static ConfigSnapshot.Database readDatabase(Reader r) {
        String host = r.string("database.host");
        Integer port = r.integer("database.port");
        String name = r.string("database.name");
        String username = r.string("database.username");
        String password = r.string("database.password");
        Integer maximumPoolSize = r.integer("database.pool.maximum-size");
        Integer minimumIdle = r.integer("database.pool.minimum-idle");
        Long connectionTimeout = r.longValue("database.pool.connection-timeout-millis");
        Long validationTimeout = r.longValue("database.pool.validation-timeout-millis");
        Long shutdownTimeout = r.longValue("database.executor.shutdown-timeout-millis");
        r.notBlank(host, "database.host");
        r.require(port != null && port >= 1 && port <= 65535, "database.port", "must be between 1 and 65535");
        r.require(name != null && name.matches("[A-Za-z0-9_]+"), "database.name", "must be a valid database identifier");
        r.notBlank(username, "database.username");
        r.require(password != null, "database.password", "is required");
        r.positive(maximumPoolSize, "database.pool.maximum-size");
        r.require(minimumIdle != null && minimumIdle >= 0, "database.pool.minimum-idle", "must be zero or greater");
        r.require(maximumPoolSize != null && minimumIdle != null && minimumIdle <= maximumPoolSize,
                "database.pool.minimum-idle", "must not exceed maximum-size");
        r.positive(connectionTimeout, "database.pool.connection-timeout-millis");
        r.positive(validationTimeout, "database.pool.validation-timeout-millis");
        r.positive(shutdownTimeout, "database.executor.shutdown-timeout-millis");
        return new ConfigSnapshot.Database(host, value(port), name, username, password, value(maximumPoolSize),
                value(minimumIdle), value(connectionTimeout), value(validationTimeout), value(shutdownTimeout));
    }

    private static ConfigSnapshot.Redis readRedis(Reader r) {
        String host = r.string("redis.host");
        Integer port = r.integer("redis.port");
        Integer database = r.integer("redis.database");
        String username = r.string("redis.username");
        String password = r.string("redis.password");
        boolean ssl = r.bool("redis.ssl");
        boolean verifyPeer = r.bool("redis.verify-peer");
        String environment = r.string("redis.environment");
        String keyPrefix = r.string("redis.key-prefix");
        Long commandTimeout = r.longValue("redis.command-timeout-millis");
        Long connectTimeout = r.longValue("redis.connect-timeout-millis");
        Long shutdownTimeout = r.longValue("redis.shutdown-timeout-millis");
        boolean autoReconnect = r.bool("redis.auto-reconnect");
        r.notBlank(host, "redis.host");
        r.require(port != null && port >= 1 && port <= 65535, "redis.port", "must be between 1 and 65535");
        r.require(database != null && database >= 0, "redis.database", "must be zero or greater");
        r.require(username != null, "redis.username", "is required");
        r.require(password != null, "redis.password", "is required");
        r.require(environment != null && CRAFTKIT_COMPONENT.matcher(environment).matches(), "redis.environment", "must be a valid CraftKit component");
        r.require(keyPrefix != null && CRAFTKIT_COMPONENT.matcher(keyPrefix).matches(), "redis.key-prefix", "must be a valid CraftKit component");
        r.positive(commandTimeout, "redis.command-timeout-millis");
        r.positive(connectTimeout, "redis.connect-timeout-millis");
        r.positive(shutdownTimeout, "redis.shutdown-timeout-millis");
        return new ConfigSnapshot.Redis(host, value(port), value(database), username, password, ssl, verifyPeer,
                environment, keyPrefix, value(commandTimeout), value(connectTimeout), value(shutdownTimeout), autoReconnect);
    }

    private static ConfigSnapshot.Integrations readIntegrations(Reader r) {
        return new ConfigSnapshot.Integrations(
                r.bool("integrations.network-boosters.enabled"),
                r.bool("integrations.luckperms.enabled"),
                r.bool("integrations.network-player-settings.enabled"),
                r.bool("placeholder-api.enabled")
        );
    }

    private static ConfigSnapshot.Currency readCurrency(Reader r) {
        String singular = r.string("currency.display-name.singular");
        String plural = r.string("currency.display-name.plural");
        String symbol = r.string("currency.symbol");
        String display = r.string("currency.display-format");
        r.notBlank(singular, "currency.display-name.singular");
        r.notBlank(plural, "currency.display-name.plural");
        r.notBlank(symbol, "currency.symbol");
        r.require(display != null && display.contains("<amount>"), "currency.display-format", "must contain <amount>");
        return new ConfigSnapshot.Currency(singular, plural, symbol, display);
    }

    private static ConfigSnapshot.MonetaryPolicy readMonetaryPolicy(Reader r) {
        BigDecimal maximum = r.decimal("currency.maximum-balance");
        r.money(maximum, "currency.maximum-balance", false);
        return new ConfigSnapshot.MonetaryPolicy(scaleMoney(maximum));
    }

    private static ConfigSnapshot.AmountInput readAmountInput(Reader r) {
        Map<String, BigDecimal> suffixes = new LinkedHashMap<>();
        Set<String> seenSuffixes = new HashSet<>();
        Section section = r.section("amount-input.suffixes");
        if (section != null) {
            for (Object key : section.getKeys()) {
                String suffix = String.valueOf(key);
                BigDecimal multiplier = r.decimal("amount-input.suffixes." + suffix);
                boolean validSuffix = suffix.matches("[A-Za-z]+");
                r.require(validSuffix, "amount-input.suffixes." + suffix, "must contain letters only");
                r.require(multiplier != null && multiplier.compareTo(BigDecimal.ONE) > 0,
                        "amount-input.suffixes." + suffix, "must be greater than 1");
                String normalized = suffix.toLowerCase(Locale.ROOT);
                boolean unique = seenSuffixes.add(normalized);
                r.require(unique, "amount-input.suffixes." + suffix, "duplicates another suffix ignoring case");
                if (validSuffix && multiplier != null && multiplier.compareTo(BigDecimal.ONE) > 0 && unique) {
                    suffixes.put(normalized, multiplier);
                }
            }
        }
        r.require(!suffixes.isEmpty(), "amount-input.suffixes", "must contain at least one suffix");
        LinkedHashMap<String, BigDecimal> ordered = new LinkedHashMap<>();
        suffixes.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return new ConfigSnapshot.AmountInput(ordered);
    }

    private static ConfigSnapshot.AmountFormat readAmountFormat(Reader r) {
        ConfigSnapshot.DisplayMode mode = r.enumValue("amount-format.default-mode", ConfigSnapshot.DisplayMode.class);
        String groupedPattern = r.string("amount-format.grouped-pattern");
        Character grouping = r.character("amount-format.grouping-separator");
        Character decimal = r.character("amount-format.decimal-separator");
        validateDecimalPattern(r, "amount-format.grouped-pattern", groupedPattern, grouping, decimal);
        List<ConfigSnapshot.CompactTier> tiers = new ArrayList<>();
        List<Map<?, ?>> rawTiers = r.mapList("amount-format.compact");
        for (int index = 0; index < rawTiers.size(); index++) {
            Map<?, ?> raw = rawTiers.get(index);
            String base = "amount-format.compact[" + index + "]";
            BigDecimal threshold = r.decimal(raw.get("threshold"), base + ".threshold");
            String pattern = r.string(raw.get("pattern"), base + ".pattern");
            String suffix = r.string(raw.get("suffix"), base + ".suffix");
            String display = r.string(raw.get("display"), base + ".display");
            r.require(threshold != null && threshold.compareTo(BigDecimal.ZERO) > 0, base + ".threshold", "must be positive");
            validateDecimalPattern(r, base + ".pattern", pattern, grouping, decimal);
            r.notBlank(suffix, base + ".suffix");
            r.require(display != null && display.contains("<amount>"), base + ".display", "must contain <amount>");
            if (threshold != null && pattern != null && suffix != null && display != null) {
                tiers.add(new ConfigSnapshot.CompactTier(threshold, pattern, suffix, display));
            }
        }
        r.require(!tiers.isEmpty(), "amount-format.compact", "must contain at least one tier");
        return new ConfigSnapshot.AmountFormat(mode, groupedPattern, value(grouping), value(decimal), tiers);
    }

    private static void validateDecimalPattern(Reader r, String path, String pattern, Character grouping, Character decimal) {
        if (pattern == null || grouping == null || decimal == null) {
            return;
        }
        try {
            DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ROOT);
            symbols.setGroupingSeparator(grouping);
            symbols.setDecimalSeparator(decimal);
            new DecimalFormat(pattern, symbols);
        } catch (IllegalArgumentException exception) {
            r.error(path, "is not a valid DecimalFormat pattern");
        }
    }

    private static ConfigSnapshot.Payments readPayments(Reader r) {
        boolean enabled = r.bool("payments.enabled");
        boolean offline = r.bool("payments.allow-offline-recipients");
        BigDecimal minimum = r.decimal("payments.minimum-amount");
        BigDecimal maximum = r.decimal("payments.maximum-amount");
        Long cooldown = r.longValue("payments.cooldown-millis");
        boolean confirmationEnabled = r.bool("payments.confirmation.enabled");
        BigDecimal confirmationMinimum = r.decimal("payments.confirmation.minimum-amount");
        Long expires = r.longValue("payments.confirmation.expires-after-seconds");
        r.money(minimum, "payments.minimum-amount", true);
        r.money(maximum, "payments.maximum-amount", true);
        r.money(confirmationMinimum, "payments.confirmation.minimum-amount", true);
        r.positive(cooldown, "payments.cooldown-millis");
        r.positive(expires, "payments.confirmation.expires-after-seconds");
        return new ConfigSnapshot.Payments(enabled, offline, scaleMoney(minimum), scaleMoney(maximum), value(cooldown),
                new ConfigSnapshot.Confirmation(confirmationEnabled, scaleMoney(confirmationMinimum), value(expires)));
    }

    private static ConfigSnapshot.Cache readCache(Reader r) {
        Long maximum = r.longValue("cache.maximum-size");
        Long refresh = r.longValue("cache.refresh-after-write-seconds");
        Long expire = r.longValue("cache.expire-after-access-minutes");
        r.positive(maximum, "cache.maximum-size");
        r.positive(refresh, "cache.refresh-after-write-seconds");
        r.positive(expire, "cache.expire-after-access-minutes");
        return new ConfigSnapshot.Cache(value(maximum), value(refresh), value(expire));
    }

    private static ConfigSnapshot.Audit readAudit(Reader r) {
        Integer retention = r.integer("audit.retention-days");
        Integer interval = r.integer("audit.cleanup-interval-hours");
        Integer batch = r.integer("audit.cleanup-batch-size");
        Integer page = r.integer("audit.command-page-size");
        Integer maximumPage = r.integer("audit.maximum-command-page");
        r.positive(retention, "audit.retention-days");
        r.positive(interval, "audit.cleanup-interval-hours");
        r.positive(batch, "audit.cleanup-batch-size");
        r.positive(page, "audit.command-page-size");
        r.positive(maximumPage, "audit.maximum-command-page");
        return new ConfigSnapshot.Audit(value(retention), value(interval), value(batch), value(page), value(maximumPage));
    }

    private static ConfigSnapshot.Identity readIdentity(Reader r) {
        String format = r.string("identity.format");
        String prefixSuffix = r.string("identity.prefix-suffix");
        String flagPrefix = r.string("identity.flag-prefix");
        ConfigSnapshot.PrefixFormat prefixFormat = r.enumValue("identity.luckperms-prefix-format", ConfigSnapshot.PrefixFormat.class);
        r.require(format != null && format.contains("<nick>"), "identity.format", "must contain <nick>");
        r.require(prefixSuffix != null, "identity.prefix-suffix", "is required");
        r.require(flagPrefix != null, "identity.flag-prefix", "is required");
        return new ConfigSnapshot.Identity(format, prefixSuffix, flagPrefix, prefixFormat);
    }

    private static ConfigSnapshot.Commands readCommands(Reader r) {
        ConfigSnapshot.Command root = readCommand(r, "root");
        Map<String, ConfigSnapshot.Command> entries = new LinkedHashMap<>();
        Set<String> literals = new HashSet<>();
        addCommandLiterals(r, "root", root, literals);
        for (String key : COMMAND_KEYS) {
            ConfigSnapshot.Command command = readCommand(r, "commands." + key);
            addCommandLiterals(r, "commands." + key, command, literals);
            entries.put(key, command);
        }
        return new ConfigSnapshot.Commands(root, entries);
    }

    private static ConfigSnapshot.Command readCommand(Reader r, String path) {
        String name = r.string(path + ".name");
        List<String> aliases = r.stringList(path + ".aliases");
        String permission = r.string(path + ".permission");
        boolean enabled = r.bool(path + ".enabled");
        String description = r.string(path + ".description");
        r.require(name != null && COMMAND_NAME.matcher(name).matches(), path + ".name", "must be a valid lowercase command literal");
        for (int index = 0; index < aliases.size(); index++) {
            String alias = aliases.get(index);
            r.require(COMMAND_NAME.matcher(alias).matches(), path + ".aliases[" + index + "]", "must be a valid lowercase command literal");
        }
        r.require(permission != null && PERMISSION.matcher(permission).matches(), path + ".permission", "must be a valid permission node");
        r.notBlank(description, path + ".description");
        return new ConfigSnapshot.Command(name, aliases, permission, enabled, description);
    }

    private static void addCommandLiterals(Reader r, String path, ConfigSnapshot.Command command, Set<String> literals) {
        if (command.name() != null && !literals.add(command.name().toLowerCase(Locale.ROOT))) {
            r.error(path + ".name", "duplicates another command name or alias");
        }
        for (int index = 0; index < command.aliases().size(); index++) {
            if (!literals.add(command.aliases().get(index).toLowerCase(Locale.ROOT))) {
                r.error(path + ".aliases[" + index + "]", "duplicates another command name or alias");
            }
        }
    }

    private static ConfigSnapshot.Messages readMessages(String file, YamlDocument document, List<String> errors) {
        Reader r = new Reader(file, document, errors);
        validateVersion(r);
        Map<String, List<Map<String, Object>>> messages = new LinkedHashMap<>();
        for (Object rawKey : document.getKeys()) {
            String key = String.valueOf(rawKey);
            if (key.equals("config-version")) {
                continue;
            }
            if (!COMPONENT_ID.matcher(key).matches()) {
                r.error(key, "must be a valid message ID");
                continue;
            }
            List<Map<?, ?>> actions = r.mapList(key);
            List<Map<String, Object>> copies = new ArrayList<>();
            for (int index = 0; index < actions.size(); index++) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : actions.get(index).entrySet()) {
                    if (!(entry.getKey() instanceof String actionKey)) {
                        r.error(key + "[" + index + "]", "action keys must be strings");
                    } else {
                        copy.put(actionKey, entry.getValue());
                    }
                }
                copies.add(copy);
            }
            messages.put(key, copies);
        }
        r.require(!messages.isEmpty(), "<root>", "must contain at least one message");
        return new ConfigSnapshot.Messages(messages);
    }

    private static void validateCrossSection(
            Reader r,
            ConfigSnapshot.MonetaryPolicy monetaryPolicy,
            ConfigSnapshot.AmountInput input,
            ConfigSnapshot.AmountFormat format,
            ConfigSnapshot.Payments payments,
            ConfigSnapshot.Cache cache,
            ConfigSnapshot.Audit audit,
            ConfigSnapshot.Identity identity
    ) {
        if (format.groupingSeparator() == format.decimalSeparator()) {
            r.error("amount-format.decimal-separator", "must differ from grouping-separator");
        }
        validateIncreasing(r, input.suffixes().entrySet().stream().map(entry -> Map.entry(entry.getKey(), entry.getValue())).toList(),
                "amount-input.suffixes");
        List<Map.Entry<String, BigDecimal>> tiers = format.compactTiers().stream()
                .map(tier -> Map.entry(tier.suffix(), tier.threshold())).toList();
        validateIncreasing(r, tiers, "amount-format.compact");
        if (payments.minimumAmount() != null && payments.maximumAmount() != null) {
            r.require(payments.maximumAmount().compareTo(payments.minimumAmount()) >= 0,
                    "payments.maximum-amount", "must be greater than or equal to minimum-amount");
        }
        if (payments.maximumAmount() != null && monetaryPolicy.maximumBalance() != null) {
            r.require(payments.maximumAmount().compareTo(monetaryPolicy.maximumBalance()) <= 0,
                    "payments.maximum-amount", "must not exceed currency.maximum-balance");
        }
        BigDecimal confirmationMinimum = payments.confirmation().minimumAmount();
        if (payments.confirmation().enabled() && confirmationMinimum != null && payments.minimumAmount() != null && payments.maximumAmount() != null) {
            r.require(confirmationMinimum.compareTo(payments.minimumAmount()) >= 0 && confirmationMinimum.compareTo(payments.maximumAmount()) <= 0,
                    "payments.confirmation.minimum-amount", "must be within the payment limits");
        }
        r.require(audit.commandPageSize() <= audit.cleanupBatchSize(), "audit.command-page-size", "must not exceed cleanup-batch-size");
        // Force access to every parsed section here; this method is the single cross-section validation boundary.
        r.require(cache.maximumSize() > 0, "cache.maximum-size", "must be positive");
        r.require(identity.format() != null, "identity.format", "is required");
    }

    private static void validateIncreasing(Reader r, List<Map.Entry<String, BigDecimal>> values, String path) {
        Set<String> suffixes = new HashSet<>();
        Set<BigDecimal> thresholds = new HashSet<>();
        BigDecimal previous = null;
        for (int index = 0; index < values.size(); index++) {
            Map.Entry<String, BigDecimal> value = values.get(index);
            String suffix = value.getKey().toLowerCase(Locale.ROOT);
            BigDecimal threshold = value.getValue();
            r.require(suffixes.add(suffix), path + "[" + index + "]", "suffixes must be unique ignoring case");
            if (threshold != null) {
                r.require(thresholds.add(threshold.stripTrailingZeros()), path + "[" + index + "]", "thresholds must be unique");
                r.require(previous == null || threshold.compareTo(previous) > 0, path + "[" + index + "]", "thresholds must be strictly increasing");
                previous = threshold;
            }
        }
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static BigDecimal scaleMoney(BigDecimal value) {
        return value == null ? null : value.setScale(2);
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    private static char value(Character value) {
        return value == null ? '\0' : value;
    }

    private static final class Reader {
        private final String file;
        private final YamlDocument document;
        private final List<String> errors;

        private Reader(String file, YamlDocument document, List<String> errors) {
            this.file = file;
            this.document = document;
            this.errors = errors;
        }

        private Object raw(String path) {
            Block<?> block = document.getBlock(path);
            if (block == null || block.isSection()) {
                error(path, "is required and must be a scalar");
                return null;
            }
            return block.getStoredValue();
        }

        private String string(String path) {
            return string(raw(path), path);
        }

        private String string(Object raw, String path) {
            if (raw instanceof String value) {
                return value;
            }
            error(path, "must be a string");
            return null;
        }

        private Character character(String path) {
            String value = string(path);
            if (value == null || value.length() != 1) {
                error(path, "must contain exactly one character");
                return null;
            }
            return value.charAt(0);
        }

        private boolean bool(String path) {
            Object raw = raw(path);
            if (raw instanceof Boolean value) {
                return value;
            }
            error(path, "must be a boolean");
            return false;
        }

        private Integer integer(String path) {
            return number(path, Integer.class, Number::intValue);
        }

        private Long longValue(String path) {
            return number(path, Long.class, Number::longValue);
        }

        private <T> T number(String path, Class<T> type, Function<Number, T> converter) {
            Object raw = raw(path);
            if (raw instanceof Number number && new BigDecimal(number.toString()).stripTrailingZeros().scale() <= 0) {
                try {
                    BigDecimal decimal = new BigDecimal(number.toString());
                    if (type == Integer.class) {
                        return type.cast(decimal.intValueExact());
                    }
                    if (type == Long.class) {
                        return type.cast(decimal.longValueExact());
                    }
                    return converter.apply(number);
                } catch (ArithmeticException exception) {
                    // Report below.
                }
            }
            error(path, "must be an integer in range");
            return null;
        }

        private BigDecimal decimal(String path) {
            return decimal(raw(path), path);
        }

        private BigDecimal decimal(Object raw, String path) {
            if (raw instanceof Number number) {
                try {
                    return new BigDecimal(number.toString());
                } catch (NumberFormatException ignored) {
                    // Report below.
                }
            }
            error(path, "must be a decimal number");
            return null;
        }

        private <E extends Enum<E>> E enumValue(String path, Class<E> type) {
            String value = string(path);
            if (value != null) {
                try {
                    return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // Report below.
                }
            }
            error(path, "must be one of " + List.of(type.getEnumConstants()));
            return type.getEnumConstants()[0];
        }

        private Section section(String path) {
            Section section = document.getSection(path);
            if (section == null) {
                error(path, "must be a section");
            }
            return section;
        }

        private List<String> stringList(String path) {
            Object raw = raw(path);
            if (!(raw instanceof List<?> list)) {
                error(path, "must be a list of strings");
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (int index = 0; index < list.size(); index++) {
                if (list.get(index) instanceof String value) {
                    result.add(value);
                } else {
                    error(path + "[" + index + "]", "must be a string");
                }
            }
            return result;
        }

        private List<Map<?, ?>> mapList(String path) {
            Object raw = raw(path);
            if (!(raw instanceof List<?> list)) {
                error(path, "must be a list of mappings");
                return List.of();
            }
            List<Map<?, ?>> result = new ArrayList<>();
            for (int index = 0; index < list.size(); index++) {
                if (list.get(index) instanceof Map<?, ?> map) {
                    result.add(map);
                } else {
                    error(path + "[" + index + "]", "must be a mapping");
                }
            }
            return result;
        }

        private void money(BigDecimal value, String path, boolean positive) {
            require(value != null && value.scale() <= 2, path, "must have at most two decimal places");
            require(value != null && (positive ? value.signum() > 0 : value.signum() >= 0), path,
                    positive ? "must be positive" : "must not be negative");
            require(value != null && value.compareTo(DECIMAL_30_2_MAX) <= 0, path, "exceeds DECIMAL(30,2)");
        }

        private void notBlank(String value, String path) {
            require(value != null && !value.isBlank(), path, "must not be blank");
        }

        private void positive(Number value, String path) {
            require(value != null && value.longValue() > 0, path, "must be positive");
        }

        private void require(boolean condition, String path, String message) {
            if (!condition) {
                error(path, message);
            }
        }

        private void error(String path, String message) {
            errors.add(file + ":" + path + ": " + message);
        }
    }
}
