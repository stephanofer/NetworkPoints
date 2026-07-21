package com.stephanofer.networkpoints.persistence;

import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import com.stephanofer.networkpoints.api.source.MutationContext;
import com.stephanofer.networkpoints.award.AppliedAwardBoost;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public record OperationRecord(
        UUID operationId,
        OperationType type,
        UUID accountId,
        Optional<UUID> counterpartyId,
        BigDecimal requestAmount,
        MutationContext context,
        Optional<String> awardGameId,
        Optional<String> awardServerId,
        BalanceSnapshot accountBefore,
        BalanceSnapshot accountAfter,
        Optional<BalanceSnapshot> counterpartyBefore,
        Optional<BalanceSnapshot> counterpartyAfter,
        BigDecimal delta,
        BigDecimal baseAmount,
        BigDecimal multiplier,
        BigDecimal finalAmount,
        List<AppliedAwardBoost> appliedBoosts) {

    public OperationRecord {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(accountId, "accountId");
        counterpartyId = Objects.requireNonNull(counterpartyId, "counterpartyId");
        Objects.requireNonNull(requestAmount, "requestAmount");
        Objects.requireNonNull(context, "context");
        awardGameId = Objects.requireNonNull(awardGameId, "awardGameId");
        awardServerId = Objects.requireNonNull(awardServerId, "awardServerId");
        Objects.requireNonNull(accountBefore, "accountBefore");
        Objects.requireNonNull(accountAfter, "accountAfter");
        counterpartyBefore = Objects.requireNonNull(counterpartyBefore, "counterpartyBefore");
        counterpartyAfter = Objects.requireNonNull(counterpartyAfter, "counterpartyAfter");
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(baseAmount, "baseAmount");
        Objects.requireNonNull(multiplier, "multiplier");
        Objects.requireNonNull(finalAmount, "finalAmount");
        appliedBoosts = List.copyOf(Objects.requireNonNull(appliedBoosts, "appliedBoosts"));
        if (!operationId.equals(context.operationId())
                || !accountId.equals(accountBefore.playerId())
                || !accountId.equals(accountAfter.playerId())
                || counterpartyBefore.isPresent() != counterpartyAfter.isPresent()
                || counterpartyId.isPresent() != counterpartyBefore.isPresent()
                || (counterpartyId.isPresent()
                && (!counterpartyId.orElseThrow().equals(counterpartyBefore.orElseThrow().playerId())
                || !counterpartyId.orElseThrow().equals(counterpartyAfter.orElseThrow().playerId())))) {
            throw new IllegalArgumentException("operation identities do not match");
        }
        if ((awardGameId.isPresent() || awardServerId.isPresent()) != (type == OperationType.AWARD)
                || awardGameId.isPresent() != awardServerId.isPresent()) {
            throw new IllegalArgumentException("award identity does not match mutation type");
        }
        if (type != OperationType.AWARD && !appliedBoosts.isEmpty()) {
            throw new IllegalArgumentException("only awards can contain applied boosts");
        }
    }

    OperationRecord withAppliedBoosts(List<AppliedAwardBoost> boosts) {
        return new OperationRecord(operationId, type, accountId, counterpartyId, requestAmount, context,
                awardGameId, awardServerId, accountBefore, accountAfter, counterpartyBefore, counterpartyAfter,
                delta, baseAmount, multiplier, finalAmount, boosts);
    }
}
