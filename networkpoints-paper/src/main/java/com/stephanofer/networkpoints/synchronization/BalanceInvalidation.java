package com.stephanofer.networkpoints.synchronization;

import com.stephanofer.networkpoints.persistence.TransactionKind;
import java.util.Objects;
import java.util.UUID;

public record BalanceInvalidation(
        UUID operationId,
        String sourceServerId,
        UUID playerId,
        long revision,
        TransactionKind transactionKind) {
    public BalanceInvalidation {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sourceServerId, "sourceServerId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(transactionKind, "transactionKind");
        if (sourceServerId.isBlank() || revision <= 0) {
            throw new IllegalArgumentException("sourceServerId must be non-blank and revision must be positive");
        }
    }
}
