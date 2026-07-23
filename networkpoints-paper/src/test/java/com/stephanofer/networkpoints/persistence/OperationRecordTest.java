package com.stephanofer.networkpoints.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import com.stephanofer.networkpoints.api.result.MutationStatus;
import com.stephanofer.networkpoints.api.source.MutationContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

final class OperationRecordTest {

    private static final UUID OPERATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void terminalRejectionPreservesOriginalSnapshotWithoutAppliedResult() {
        BalanceSnapshot before = new BalanceSnapshot(ACCOUNT_ID, new BigDecimal("5.00"), 7L);

        OperationRecord record = rejection(MutationStatus.INSUFFICIENT_FUNDS, Optional.of(before));

        assertEquals(MutationStatus.INSUFFICIENT_FUNDS, record.outcomeStatus());
        assertEquals(Optional.of(before), record.accountBefore());
        assertEquals(Optional.empty(), record.accountAfter());
    }

    @Test
    void accountNotFoundCanBePersistedWithoutExistingAccountSnapshot() {
        OperationRecord record = rejection(MutationStatus.ACCOUNT_NOT_FOUND, Optional.empty());

        assertEquals(Optional.empty(), record.accountBefore());
    }

    @Test
    void retryableAndDerivedStatusesCannotBePersisted() {
        assertThrows(IllegalArgumentException.class,
                () -> rejection(MutationStatus.BOOSTER_STATE_NOT_READY, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> rejection(MutationStatus.SERVICE_UNAVAILABLE, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> rejection(MutationStatus.IDEMPOTENCY_CONFLICT, Optional.empty()));
    }

    @Test
    void rejectionCannotContainAppliedOutcome() {
        BalanceSnapshot before = new BalanceSnapshot(ACCOUNT_ID, new BigDecimal("5.00"), 7L);
        BalanceSnapshot after = new BalanceSnapshot(ACCOUNT_ID, new BigDecimal("4.00"), 8L);

        assertThrows(IllegalArgumentException.class, () -> new OperationRecord(
                OPERATION_ID, OperationType.DEBIT, MutationStatus.INSUFFICIENT_FUNDS, ACCOUNT_ID, Optional.empty(),
                new BigDecimal("10.00"), context(), Optional.empty(), Optional.empty(), Optional.of(before),
                Optional.of(after), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of()));
    }

    private static OperationRecord rejection(MutationStatus status, Optional<BalanceSnapshot> before) {
        return new OperationRecord(
                OPERATION_ID, OperationType.DEBIT, status, ACCOUNT_ID, Optional.empty(), new BigDecimal("10.00"),
                context(), Optional.empty(), Optional.empty(), before, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }

    private static MutationContext context() {
        return new MutationContext(OPERATION_ID, Key.key("networkcosmetics:purchase"), Optional.empty(),
                Optional.of("purchase-7"));
    }
}
