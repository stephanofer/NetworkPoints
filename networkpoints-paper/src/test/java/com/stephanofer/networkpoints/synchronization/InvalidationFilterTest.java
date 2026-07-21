package com.stephanofer.networkpoints.synchronization;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stephanofer.networkpoints.persistence.TransactionKind;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvalidationFilterTest {

    @Test
    void rejectsOwnStaleUncachedAndDuplicateInvalidations() {
        InvalidationFilter filter = new InvalidationFilter("lobby-1");
        UUID operationId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        BalanceInvalidation remote = event(operationId, "games-1", playerId, 3);

        assertFalse(filter.shouldRefresh(event(operationId, "lobby-1", playerId, 3), true, 1));
        assertFalse(filter.shouldRefresh(remote, false, 1));
        assertFalse(filter.shouldRefresh(remote, true, 3));
        assertTrue(filter.shouldRefresh(remote, true, 2));
        assertFalse(filter.shouldRefresh(remote, true, 2));
        filter.close();
    }

    @Test
    void acceptsBothAccountsOfOneTransferOperation() {
        InvalidationFilter filter = new InvalidationFilter("lobby-1");
        UUID operationId = UUID.randomUUID();

        assertTrue(filter.shouldRefresh(event(operationId, "games-1", UUID.randomUUID(), 2), true, 1));
        assertTrue(filter.shouldRefresh(event(operationId, "games-1", UUID.randomUUID(), 2), true, 1));
        filter.close();
    }

    private static BalanceInvalidation event(UUID operationId, String serverId, UUID playerId, long revision) {
        return new BalanceInvalidation(operationId, serverId, playerId, revision, TransactionKind.CREDIT);
    }
}
