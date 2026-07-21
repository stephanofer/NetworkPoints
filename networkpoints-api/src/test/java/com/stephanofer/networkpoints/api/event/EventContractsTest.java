package com.stephanofer.networkpoints.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import com.stephanofer.networkpoints.api.result.MutationType;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventContractsTest {
    @Test
    void balanceChangeCarriesTypeAndRequiresItsDeltaSemantics() {
        UUID playerId = UUID.randomUUID();
        BalanceSnapshot before = snapshot(playerId, "10", 1);
        BalanceSnapshot after = snapshot(playerId, "8", 2);

        PointsBalanceChangeEvent event =
                new PointsBalanceChangeEvent(UUID.randomUUID(), MutationType.DEBIT, before, after, bd("-2"));

        assertEquals(MutationType.DEBIT, event.type());
        assertThrows(IllegalArgumentException.class,
                () -> new PointsBalanceChangeEvent(
                        UUID.randomUUID(), MutationType.CREDIT, before, after, bd("-2")));
    }

    @Test
    void awardRequiresRoundedMultiplicationAndAdvancingRevision() {
        UUID playerId = UUID.randomUUID();
        BalanceSnapshot before = snapshot(playerId, "10", 1);
        BalanceSnapshot after = snapshot(playerId, "13.34", 2);

        new PointsAwardEvent(UUID.randomUUID(), before, after, bd("2.67"), bd("1.25"), bd("3.34"));

        assertThrows(IllegalArgumentException.class,
                () -> new PointsAwardEvent(
                        UUID.randomUUID(), before, after, bd("2.67"), bd("1.24"), bd("3.34")));
        assertThrows(IllegalArgumentException.class,
                () -> new PointsAwardEvent(
                        UUID.randomUUID(), before, snapshot(playerId, "13.34", 1),
                        bd("2.67"), bd("1.25"), bd("3.34")));
    }

    @Test
    void transferRequiresBothRevisionsToAdvance() {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> new PointsTransferEvent(
                UUID.randomUUID(),
                snapshot(senderId, "10", 1),
                snapshot(senderId, "8", 1),
                snapshot(recipientId, "1", 1),
                snapshot(recipientId, "3", 2),
                bd("2")));
    }

    private static BalanceSnapshot snapshot(UUID playerId, String balance, long revision) {
        return new BalanceSnapshot(playerId, bd(balance), revision);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
