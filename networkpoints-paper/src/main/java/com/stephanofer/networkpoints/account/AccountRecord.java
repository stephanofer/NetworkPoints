package com.stephanofer.networkpoints.account;

import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record AccountRecord(UUID playerId, String lastKnownName, Optional<String> normalizedName, BalanceSnapshot snapshot) {
    public AccountRecord {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(lastKnownName, "lastKnownName");
        normalizedName = Objects.requireNonNull(normalizedName, "normalizedName");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.playerId().equals(playerId)) {
            throw new IllegalArgumentException("snapshot must belong to playerId");
        }
    }
}
