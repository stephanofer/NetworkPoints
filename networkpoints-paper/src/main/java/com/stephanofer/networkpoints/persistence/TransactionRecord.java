package com.stephanofer.networkpoints.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record TransactionRecord(
        long id,
        UUID operationId,
        int entryIndex,
        UUID accountId,
        Optional<UUID> counterpartyId,
        TransactionKind kind,
        BigDecimal delta,
        Optional<BigDecimal> baseAmount,
        Optional<BigDecimal> multiplier,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        long revisionBefore,
        long revisionAfter,
        Optional<UUID> actorId,
        String source,
        Optional<String> sourceReference,
        String sourceServerId,
        Instant createdAt) {
    public TransactionRecord {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(accountId, "accountId");
        counterpartyId = Objects.requireNonNull(counterpartyId, "counterpartyId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(delta, "delta");
        baseAmount = Objects.requireNonNull(baseAmount, "baseAmount");
        multiplier = Objects.requireNonNull(multiplier, "multiplier");
        Objects.requireNonNull(balanceBefore, "balanceBefore");
        Objects.requireNonNull(balanceAfter, "balanceAfter");
        actorId = Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(source, "source");
        sourceReference = Objects.requireNonNull(sourceReference, "sourceReference");
        Objects.requireNonNull(sourceServerId, "sourceServerId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (entryIndex < 0 || entryIndex > 255 || revisionBefore < 0 || revisionAfter != revisionBefore + 1) {
            throw new IllegalArgumentException("invalid transaction entry or revision");
        }
    }
}
