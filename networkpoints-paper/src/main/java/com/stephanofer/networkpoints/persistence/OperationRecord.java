package com.stephanofer.networkpoints.persistence;

import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import com.stephanofer.networkpoints.api.result.MutationStatus;
import com.stephanofer.networkpoints.api.source.MutationContext;
import com.stephanofer.networkpoints.award.AppliedAwardBoost;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record OperationRecord(
        UUID operationId,
        OperationType type,
        MutationStatus outcomeStatus,
        UUID accountId,
        Optional<UUID> counterpartyId,
        BigDecimal requestAmount,
        MutationContext context,
        Optional<String> awardGameId,
        Optional<String> awardServerId,
        Optional<BalanceSnapshot> accountBefore,
        Optional<BalanceSnapshot> accountAfter,
        Optional<BalanceSnapshot> counterpartyBefore,
        Optional<BalanceSnapshot> counterpartyAfter,
        Optional<BigDecimal> delta,
        Optional<BigDecimal> baseAmount,
        Optional<BigDecimal> multiplier,
        Optional<BigDecimal> finalAmount,
        List<AppliedAwardBoost> appliedBoosts) {

    public OperationRecord {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(outcomeStatus, "outcomeStatus");
        Objects.requireNonNull(accountId, "accountId");
        counterpartyId = Objects.requireNonNull(counterpartyId, "counterpartyId");
        Objects.requireNonNull(requestAmount, "requestAmount");
        Objects.requireNonNull(context, "context");
        awardGameId = Objects.requireNonNull(awardGameId, "awardGameId");
        awardServerId = Objects.requireNonNull(awardServerId, "awardServerId");
        accountBefore = Objects.requireNonNull(accountBefore, "accountBefore");
        accountAfter = Objects.requireNonNull(accountAfter, "accountAfter");
        counterpartyBefore = Objects.requireNonNull(counterpartyBefore, "counterpartyBefore");
        counterpartyAfter = Objects.requireNonNull(counterpartyAfter, "counterpartyAfter");
        delta = Objects.requireNonNull(delta, "delta");
        baseAmount = Objects.requireNonNull(baseAmount, "baseAmount");
        multiplier = Objects.requireNonNull(multiplier, "multiplier");
        finalAmount = Objects.requireNonNull(finalAmount, "finalAmount");
        appliedBoosts = List.copyOf(Objects.requireNonNull(appliedBoosts, "appliedBoosts"));

        if ((type == OperationType.TRANSFER) != counterpartyId.isPresent()) {
            throw new IllegalArgumentException("counterparty identity does not match mutation type");
        }
        UUID counterparty = counterpartyId.orElse(null);
        if (!operationId.equals(context.operationId())
                || accountBefore.stream().anyMatch(snapshot -> !accountId.equals(snapshot.playerId()))
                || accountAfter.stream().anyMatch(snapshot -> !accountId.equals(snapshot.playerId()))
                || counterpartyBefore.stream().anyMatch(snapshot -> !counterparty.equals(snapshot.playerId()))
                || counterpartyAfter.stream().anyMatch(snapshot -> !counterparty.equals(snapshot.playerId()))) {
            throw new IllegalArgumentException("operation identities do not match");
        }
        if ((awardGameId.isPresent() || awardServerId.isPresent()) != (type == OperationType.AWARD)
                || awardGameId.isPresent() != awardServerId.isPresent()) {
            throw new IllegalArgumentException("award identity does not match mutation type");
        }
        if (outcomeStatus == MutationStatus.SUCCESS) {
            requireSuccessful(accountBefore, accountAfter, counterpartyId, counterpartyBefore, counterpartyAfter,
                    delta, baseAmount, multiplier, finalAmount);
        } else {
            requireRejected(outcomeStatus, accountAfter, counterpartyBefore, counterpartyAfter,
                    delta, baseAmount, multiplier, finalAmount, appliedBoosts);
        }
        if (type != OperationType.AWARD && !appliedBoosts.isEmpty()) {
            throw new IllegalArgumentException("only awards can contain applied boosts");
        }
    }

    OperationRecord withAppliedBoosts(List<AppliedAwardBoost> boosts) {
        return new OperationRecord(operationId, type, outcomeStatus, accountId, counterpartyId, requestAmount,
                context, awardGameId, awardServerId, accountBefore, accountAfter, counterpartyBefore,
                counterpartyAfter, delta, baseAmount, multiplier, finalAmount, boosts);
    }

    private static void requireSuccessful(
            Optional<BalanceSnapshot> accountBefore,
            Optional<BalanceSnapshot> accountAfter,
            Optional<UUID> counterpartyId,
            Optional<BalanceSnapshot> counterpartyBefore,
            Optional<BalanceSnapshot> counterpartyAfter,
            Optional<BigDecimal> delta,
            Optional<BigDecimal> baseAmount,
            Optional<BigDecimal> multiplier,
            Optional<BigDecimal> finalAmount) {
        if (accountBefore.isEmpty() || accountAfter.isEmpty() || delta.isEmpty() || baseAmount.isEmpty()
                || multiplier.isEmpty() || finalAmount.isEmpty()
                || counterpartyId.isPresent() != counterpartyBefore.isPresent()
                || counterpartyId.isPresent() != counterpartyAfter.isPresent()) {
            throw new IllegalArgumentException("successful operation details are incomplete");
        }
        if (accountAfter.orElseThrow().revision() != accountBefore.orElseThrow().revision() + 1
                || (counterpartyId.isPresent() && counterpartyAfter.orElseThrow().revision()
                != counterpartyBefore.orElseThrow().revision() + 1)) {
            throw new IllegalArgumentException("successful operation revisions must advance by one");
        }
    }

    private static void requireRejected(
            MutationStatus status,
            Optional<BalanceSnapshot> accountAfter,
            Optional<BalanceSnapshot> counterpartyBefore,
            Optional<BalanceSnapshot> counterpartyAfter,
            Optional<BigDecimal> delta,
            Optional<BigDecimal> baseAmount,
            Optional<BigDecimal> multiplier,
            Optional<BigDecimal> finalAmount,
            List<AppliedAwardBoost> appliedBoosts) {
        if (status != MutationStatus.INSUFFICIENT_FUNDS
                && status != MutationStatus.BALANCE_LIMIT_EXCEEDED
                && status != MutationStatus.ACCOUNT_NOT_FOUND) {
            throw new IllegalArgumentException("operation status is not persistable");
        }
        if (accountAfter.isPresent() || counterpartyBefore.isPresent() || counterpartyAfter.isPresent()
                || delta.isPresent() || baseAmount.isPresent() || multiplier.isPresent() || finalAmount.isPresent()
                || !appliedBoosts.isEmpty()) {
            throw new IllegalArgumentException("rejected operation cannot contain applied results");
        }
    }
}
