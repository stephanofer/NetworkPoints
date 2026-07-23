package com.stephanofer.networkpoints.account;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import com.stephanofer.networkpoints.api.result.MutationStatus;
import com.stephanofer.networkpoints.api.source.MutationContext;
import com.stephanofer.networkpoints.persistence.OperationRecord;
import com.stephanofer.networkpoints.persistence.OperationType;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

final class IdempotencyMatcherTest {

    private static final UUID OPERATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void compatibleReplayMatchesEveryPersistedRequestField() {
        MutationContext context = context(Key.key("hera:test"), Optional.of(ACTOR_ID), Optional.of("match-7"));

        assertTrue(IdempotencyMatcher.matches(record(), OperationType.AWARD, ACCOUNT_ID, Optional.empty(),
                new BigDecimal("10.0"), context, Optional.of("skywars"), Optional.of("skywars-1")));
    }

    @Test
    void changedEconomicOrAuditIdentityConflicts() {
        OperationRecord record = record();
        MutationContext context = context(Key.key("hera:test"), Optional.of(ACTOR_ID), Optional.of("match-7"));

        assertFalse(IdempotencyMatcher.matches(record, OperationType.AWARD, ACCOUNT_ID, Optional.empty(),
                new BigDecimal("10.01"), context, Optional.of("skywars"), Optional.of("skywars-1")));
        assertFalse(IdempotencyMatcher.matches(record, OperationType.CREDIT, ACCOUNT_ID, Optional.empty(),
                new BigDecimal("10.00"), context, Optional.empty(), Optional.empty()));
        assertFalse(IdempotencyMatcher.matches(record, OperationType.AWARD, ACCOUNT_ID, Optional.empty(),
                new BigDecimal("10.00"), context(Key.key("hera:other"), Optional.of(ACTOR_ID), Optional.of("match-7")),
                Optional.of("skywars"), Optional.of("skywars-1")));
        assertFalse(IdempotencyMatcher.matches(record, OperationType.AWARD, ACCOUNT_ID, Optional.empty(),
                new BigDecimal("10.00"), context, Optional.of("bedwars"), Optional.of("skywars-1")));
        assertFalse(IdempotencyMatcher.matches(record, OperationType.AWARD, ACCOUNT_ID, Optional.empty(),
                new BigDecimal("10.00"), context, Optional.of("skywars"), Optional.of("skywars-2")));
    }

    @Test
    void rejectedOutcomeUsesTheSameCompleteRequestIdentity() {
        OperationRecord record = new OperationRecord(
                OPERATION_ID, OperationType.AWARD, MutationStatus.BALANCE_LIMIT_EXCEEDED,
                ACCOUNT_ID, Optional.empty(), new BigDecimal("10.00"),
                context(Key.key("hera:test"), Optional.of(ACTOR_ID), Optional.of("match-7")),
                Optional.of("skywars"), Optional.of("skywars-1"),
                Optional.of(new BalanceSnapshot(ACCOUNT_ID, new BigDecimal("999.00"), 8L)), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), java.util.List.of());

        assertTrue(IdempotencyMatcher.matches(record, OperationType.AWARD, ACCOUNT_ID, Optional.empty(),
                new BigDecimal("10.0"), context(Key.key("hera:test"), Optional.of(ACTOR_ID),
                        Optional.of("match-7")), Optional.of("skywars"), Optional.of("skywars-1")));
    }

    private static OperationRecord record() {
        BalanceSnapshot before = new BalanceSnapshot(ACCOUNT_ID, new BigDecimal("5.00"), 4L);
        BalanceSnapshot after = new BalanceSnapshot(ACCOUNT_ID, new BigDecimal("15.00"), 5L);
        return new OperationRecord(OPERATION_ID, OperationType.AWARD, MutationStatus.SUCCESS,
                ACCOUNT_ID, Optional.empty(),
                new BigDecimal("10.00"), context(Key.key("hera:test"), Optional.of(ACTOR_ID), Optional.of("match-7")),
                Optional.of("skywars"), Optional.of("skywars-1"), Optional.of(before), Optional.of(after),
                Optional.empty(), Optional.empty(), Optional.of(new BigDecimal("10.00")),
                Optional.of(new BigDecimal("10.00")), Optional.of(new BigDecimal("1.00000000")),
                Optional.of(new BigDecimal("10.00")), java.util.List.of());
    }

    private static MutationContext context(Key source, Optional<UUID> actor, Optional<String> reference) {
        return new MutationContext(OPERATION_ID, source, actor, reference);
    }
}
