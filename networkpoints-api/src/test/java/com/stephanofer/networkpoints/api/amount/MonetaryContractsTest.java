package com.stephanofer.networkpoints.api.amount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MonetaryContractsTest {
    @Test
    void normalizesAcceptedValuesToFixedScale() {
        BigDecimal normalized = MonetaryAmounts.nonNegative(new BigDecimal("12.5"), "amount");

        assertEquals(new BigDecimal("12.50"), normalized);
        assertEquals(2, normalized.scale());
    }

    @Test
    void rejectsPrecisionLossAndInvalidSigns() {
        assertThrows(IllegalArgumentException.class,
                () -> MonetaryAmounts.nonNegative(new BigDecimal("1.001"), "amount"));
        assertThrows(IllegalArgumentException.class,
                () -> MonetaryAmounts.nonNegative(new BigDecimal("-0.01"), "amount"));
        assertThrows(IllegalArgumentException.class,
                () -> MonetaryAmounts.positive(BigDecimal.ZERO, "amount"));
    }

    @Test
    void enforcesTheDurableDecimalDomainAtBothBoundaries() {
        assertEquals(MonetaryAmounts.MAX_VALUE, MonetaryAmounts.nonNegative(MonetaryAmounts.MAX_VALUE, "amount"));
        assertEquals(MonetaryAmounts.MAX_VALUE.negate(),
                MonetaryAmounts.signed(MonetaryAmounts.MAX_VALUE.negate(), "delta"));
        assertThrows(IllegalArgumentException.class,
                () -> MonetaryAmounts.nonNegative(MonetaryAmounts.MAX_VALUE.add(new BigDecimal("0.01")), "amount"));
        assertThrows(IllegalArgumentException.class,
                () -> MonetaryAmounts.signed(MonetaryAmounts.MAX_VALUE.add(new BigDecimal("0.01")).negate(), "delta"));
    }

    @Test
    void snapshotRequiresNonNegativeBalanceAndRevision() {
        UUID playerId = UUID.randomUUID();
        BalanceSnapshot snapshot = new BalanceSnapshot(playerId, new BigDecimal("7"), 0);

        assertEquals(new BigDecimal("7.00"), snapshot.balance());
        assertThrows(IllegalArgumentException.class,
                () -> new BalanceSnapshot(playerId, new BigDecimal("-0.01"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BalanceSnapshot(playerId, BigDecimal.ZERO, -1));
    }

    @Test
    void parseSuccessIsNormalizedAndFailureRemainsTyped() {
        AmountParseResult.Success success = new AmountParseResult.Success(new BigDecimal("1.5"));
        AmountParseResult failure = new AmountParseResult.Failure(AmountParseResult.Reason.UNKNOWN_SUFFIX);

        assertEquals(new BigDecimal("1.50"), success.amount());
        assertInstanceOf(AmountParseResult.Failure.class, failure);
    }
}
