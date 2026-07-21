package com.stephanofer.networkpoints.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class NetworkPointsConfig {
    private final ConfigLoader loader;
    private final AtomicReference<ConfigSnapshot> active = new AtomicReference<>();

    public NetworkPointsConfig(Path dataDirectory) {
        this.loader = new ConfigLoader(Objects.requireNonNull(dataDirectory, "dataDirectory"));
    }

    public ConfigSnapshot start() throws IOException, ConfigValidationException {
        ConfigSnapshot candidate = loader.loadCandidate();
        if (!active.compareAndSet(null, candidate)) {
            throw new IllegalStateException("Configuration has already started");
        }
        return candidate;
    }

    public ConfigSnapshot reload() throws IOException, ConfigValidationException {
        return reload(ignored -> { });
    }

    public ConfigSnapshot reload(Consumer<ConfigSnapshot.Reloadable> validator)
            throws IOException, ConfigValidationException {
        return publish(prepareReload(validator));
    }

    public ReloadCandidate prepareReload(Consumer<ConfigSnapshot.Reloadable> validator)
            throws IOException, ConfigValidationException {
        ConfigSnapshot previous = snapshot();
        ConfigSnapshot candidate = loader.loadCandidate();
        validator.accept(candidate.reloadable());
        ConfigSnapshot published = new ConfigSnapshot(
                previous.restartRequired(),
                candidate.reloadable(),
                restartRequiredChanges(previous.restartRequired(), candidate.restartRequired())
        );
        return new ReloadCandidate(previous, published);
    }

    public ConfigSnapshot publish(ReloadCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (!this.active.compareAndSet(candidate.previous(), candidate.published())) {
            throw new IllegalStateException("Configuration changed while reload was being prepared");
        }
        return candidate.published();
    }

    private static List<String> restartRequiredChanges(
            ConfigSnapshot.RestartRequired active,
            ConfigSnapshot.RestartRequired candidate
    ) {
        List<String> changes = new ArrayList<>();
        if (!Objects.equals(active.serverId(), candidate.serverId())) {
            changes.add("server-id");
        }
        if (!Objects.equals(active.database(), candidate.database())) {
            changes.add("database");
        }
        if (!Objects.equals(active.redis(), candidate.redis())) {
            changes.add("redis");
        }
        if (!Objects.equals(active.commands(), candidate.commands())) {
            changes.add("commands");
        }
        if (!Objects.equals(active.integrations(), candidate.integrations())) {
            changes.add("integrations");
        }
        if (!Objects.equals(active.monetaryPolicy(), candidate.monetaryPolicy())) {
            changes.add("currency.maximum-balance");
        }
        return List.copyOf(changes);
    }

    public ConfigSnapshot snapshot() {
        ConfigSnapshot snapshot = active.get();
        if (snapshot == null) {
            throw new IllegalStateException("Configuration has not started");
        }
        return snapshot;
    }

    public record ReloadCandidate(ConfigSnapshot previous, ConfigSnapshot published) {
        public ReloadCandidate {
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(published, "published");
        }
    }
}
