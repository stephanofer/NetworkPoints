package com.stephanofer.networkpoints.synchronization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.stephanofer.networkpoints.persistence.TransactionKind;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BalanceInvalidationCodecTest {
    private final BalanceInvalidationCodec codec = new BalanceInvalidationCodec();

    @Test
    void roundTripsVersionedPayload() {
        BalanceInvalidation event = new BalanceInvalidation(
                UUID.randomUUID(), "skywars-1", UUID.randomUUID(), 42, TransactionKind.AWARD);

        assertEquals(event, codec.decode(codec.encode(event)));
    }

    @Test
    void rejectsUnknownVersionsMalformedFieldsAndOversizedPayloads() {
        UUID operationId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                "2|" + operationId + "|lobby-1|" + playerId + "|1|CREDIT"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                "1|" + operationId + "|INVALID SERVER|" + playerId + "|1|CREDIT"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                "1|" + operationId + "|lobby-1|" + playerId + "|0|CREDIT"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("x".repeat(513)));
    }
}
