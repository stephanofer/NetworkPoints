package com.stephanofer.networkpoints.api.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResultContractsTest {
    @Test
    void exposesEveryFrozenStatus() {
        assertEquals(EnumSet.of(
                        MutationStatus.SUCCESS,
                        MutationStatus.INSUFFICIENT_FUNDS,
                        MutationStatus.BALANCE_LIMIT_EXCEEDED,
                        MutationStatus.INVALID_AMOUNT,
                        MutationStatus.ACCOUNT_NOT_FOUND,
                        MutationStatus.BOOSTER_STATE_NOT_READY,
                        MutationStatus.IDEMPOTENCY_CONFLICT,
                        MutationStatus.SERVICE_UNAVAILABLE),
                EnumSet.allOf(MutationStatus.class));
        assertEquals(EnumSet.of(
                        MutationType.AWARD,
                        MutationType.CREDIT,
                        MutationType.DEBIT,
                        MutationType.SET_BALANCE),
                EnumSet.allOf(MutationType.class));
    }

    @Test
    void successfulMutationRequiresCompleteConsistentBreakdown() {
        UUID playerId = UUID.randomUUID();
        BalanceSnapshot before = snapshot(playerId, "10", 1);
        BalanceSnapshot after = snapshot(playerId, "15", 2);

        MutationResult result = new MutationResult(
                MutationStatus.SUCCESS,
                MutationType.CREDIT,
                UUID.randomUUID(),
                playerId,
                Optional.of(before),
                Optional.of(after),
                Optional.of(new BigDecimal("5")),
                Optional.of(new BigDecimal("5")),
                Optional.of(BigDecimal.ONE),
                Optional.of(new BigDecimal("5")),
                false);

        assertTrue(result.success());
        assertEquals(new BigDecimal("5.00"), result.delta().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new MutationResult(
                MutationStatus.SUCCESS,
                MutationType.CREDIT,
                UUID.randomUUID(),
                playerId,
                Optional.of(before),
                Optional.of(after),
                Optional.of(new BigDecimal("4")),
                Optional.of(new BigDecimal("5")),
                Optional.of(BigDecimal.ONE),
                Optional.of(new BigDecimal("5")),
                false));
    }

    @Test
    void failedMutationCannotClaimIdempotentReplay() {
        MutationResult result = new MutationResult(
                MutationStatus.ACCOUNT_NOT_FOUND,
                MutationType.AWARD,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false);

        assertFalse(result.success());
        assertThrows(IllegalArgumentException.class, () -> new MutationResult(
                MutationStatus.SERVICE_UNAVAILABLE,
                MutationType.AWARD,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                true));
    }

    @Test
    void successfulAwardsRequireRoundedMultipliedAmount() {
        UUID playerId = UUID.randomUUID();
        BalanceSnapshot before = snapshot(playerId, "10", 4);
        BalanceSnapshot after = snapshot(playerId, "13.34", 5);

        MutationResult result = mutation(
                MutationType.AWARD, before, after, "3.34", "2.67", "1.25", "3.34");

        assertEquals(new BigDecimal("3.34"), result.finalAmount().orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> mutation(MutationType.AWARD, before, after, "3.34", "2.67", "1.24", "3.34"));
    }

    @Test
    void directMutationBreakdownsAreFrozenByType() {
        UUID playerId = UUID.randomUUID();

        mutation(
                MutationType.CREDIT,
                snapshot(playerId, "10", 1),
                snapshot(playerId, "12", 2),
                "2", "2", "1", "2");
        mutation(
                MutationType.DEBIT,
                snapshot(playerId, "10", 2),
                snapshot(playerId, "8", 3),
                "-2", "2", "1", "2");
        mutation(
                MutationType.SET_BALANCE,
                snapshot(playerId, "10", 3),
                snapshot(playerId, "4", 4),
                "-6", "4", "1", "4");
        mutation(
                MutationType.SET_BALANCE,
                snapshot(playerId, "4", 4),
                snapshot(playerId, "4", 5),
                "0", "4", "1", "4");

        assertThrows(IllegalArgumentException.class, () -> mutation(
                MutationType.CREDIT,
                snapshot(playerId, "10", 1),
                snapshot(playerId, "12", 2),
                "2", "2", "2", "2"));
        assertThrows(IllegalArgumentException.class, () -> mutation(
                MutationType.DEBIT,
                snapshot(playerId, "10", 1),
                snapshot(playerId, "8", 2),
                "-2", "-2", "1", "2"));
    }

    @Test
    void successfulMutationRequiresAdvancingRevisionAndStorableMultiplier() {
        UUID playerId = UUID.randomUUID();
        BalanceSnapshot before = snapshot(playerId, "10", 2);
        BalanceSnapshot unchangedRevision = snapshot(playerId, "12", 2);

        MutationResult boundary = mutation(
                MutationType.AWARD,
                before,
                snapshot(playerId, "12", 3),
                "2", "2", "1.000000000", "2");

        assertEquals(8, boundary.multiplier().orElseThrow().scale());
        assertThrows(IllegalArgumentException.class, () -> mutation(
                MutationType.CREDIT, before, unchangedRevision, "2", "2", "1", "2"));
        assertThrows(IllegalArgumentException.class, () -> mutation(
                MutationType.AWARD,
                before,
                snapshot(playerId, "12", 3),
                "2", "2", "1.000000001", "2"));
        assertThrows(IllegalArgumentException.class, () -> mutation(
                MutationType.AWARD,
                before,
                snapshot(playerId, "12", 3),
                "2", "2", "1000000000000", "2"));
    }

    @Test
    void successfulTransferRequiresMatchingSnapshotsAndDeltas() {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        BalanceSnapshot senderBefore = snapshot(senderId, "20", 1);
        BalanceSnapshot senderAfter = snapshot(senderId, "15", 2);
        BalanceSnapshot recipientBefore = snapshot(recipientId, "1", 1);
        BalanceSnapshot recipientAfter = snapshot(recipientId, "6", 2);

        TransferResult result = new TransferResult(
                MutationStatus.SUCCESS,
                UUID.randomUUID(),
                senderId,
                recipientId,
                Optional.of(senderBefore),
                Optional.of(senderAfter),
                Optional.of(recipientBefore),
                Optional.of(recipientAfter),
                Optional.of(new BigDecimal("5")),
                Optional.of(new BigDecimal("-5")),
                Optional.of(new BigDecimal("5")),
                Optional.of(new BigDecimal("5")),
                Optional.of(BigDecimal.ONE),
                Optional.of(new BigDecimal("5")),
                true);

        assertTrue(result.success());
        assertEquals(new BigDecimal("5.00"), result.amount().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new TransferResult(
                MutationStatus.SUCCESS,
                UUID.randomUUID(),
                senderId,
                recipientId,
                Optional.of(senderBefore),
                Optional.of(senderAfter),
                Optional.of(recipientBefore),
                Optional.of(recipientAfter),
                Optional.of(new BigDecimal("5")),
                Optional.of(new BigDecimal("-4")),
                Optional.of(new BigDecimal("5")),
                Optional.of(new BigDecimal("5")),
                Optional.of(BigDecimal.ONE),
                Optional.of(new BigDecimal("5")),
                false));
    }

    @Test
    void successfulTransferRequiresBothRevisionsToAdvance() {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> new TransferResult(
                MutationStatus.SUCCESS,
                UUID.randomUUID(),
                senderId,
                recipientId,
                Optional.of(snapshot(senderId, "20", 1)),
                Optional.of(snapshot(senderId, "15", 1)),
                Optional.of(snapshot(recipientId, "1", 1)),
                Optional.of(snapshot(recipientId, "6", 2)),
                Optional.of(new BigDecimal("5")),
                Optional.of(new BigDecimal("-5")),
                Optional.of(new BigDecimal("5")),
                Optional.of(new BigDecimal("5")),
                Optional.of(BigDecimal.ONE),
                Optional.of(new BigDecimal("5")),
                false));
    }

    private static MutationResult mutation(
            MutationType type,
            BalanceSnapshot before,
            BalanceSnapshot after,
            String delta,
            String baseAmount,
            String multiplier,
            String finalAmount) {
        return new MutationResult(
                MutationStatus.SUCCESS,
                type,
                UUID.randomUUID(),
                before.playerId(),
                Optional.of(before),
                Optional.of(after),
                Optional.of(new BigDecimal(delta)),
                Optional.of(new BigDecimal(baseAmount)),
                Optional.of(new BigDecimal(multiplier)),
                Optional.of(new BigDecimal(finalAmount)),
                false);
    }

    private static BalanceSnapshot snapshot(UUID playerId, String balance, long revision) {
        return new BalanceSnapshot(playerId, new BigDecimal(balance), revision);
    }
}
