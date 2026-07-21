package com.stephanofer.networkpoints.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

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
        ConfigSnapshot previous = snapshot();
        ConfigSnapshot candidate = loader.loadCandidate();
        ConfigSnapshot published = new ConfigSnapshot(
                previous.restartRequired(),
                candidate.reloadable(),
                restartRequiredChanges(previous.restartRequired(), candidate.restartRequired())
        );
        active.set(published);
        return published;
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
}
