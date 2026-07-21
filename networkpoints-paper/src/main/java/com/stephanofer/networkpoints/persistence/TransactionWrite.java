package com.stephanofer.networkpoints.persistence;

import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import com.stephanofer.networkpoints.api.source.MutationContext;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record TransactionWrite(
        UUID operationId,
        int entryIndex,
        UUID accountId,
        Optional<UUID> counterpartyId,
        TransactionKind kind,
        BigDecimal delta,
        BigDecimal baseAmount,
        BigDecimal multiplier,
        BalanceSnapshot before,
        BalanceSnapshot after,
        MutationContext context,
        String sourceServerId) {
    public TransactionWrite {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(accountId, "accountId");
        counterpartyId = Objects.requireNonNull(counterpartyId, "counterpartyId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(baseAmount, "baseAmount");
        Objects.requireNonNull(multiplier, "multiplier");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(sourceServerId, "sourceServerId");
        if (!operationId.equals(context.operationId()) || !accountId.equals(before.playerId())
                || !accountId.equals(after.playerId())) {
            throw new IllegalArgumentException("transaction write identities do not match");
        }
    }
}
