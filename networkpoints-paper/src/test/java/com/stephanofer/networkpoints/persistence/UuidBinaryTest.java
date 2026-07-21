package com.stephanofer.networkpoints.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.stephanofer.networkpoints.account.AccountNames;
import com.stephanofer.networkpoints.account.AccountRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class UuidBinaryTest {

    @Test
    void binaryRoundTripPreservesAllUuidBits() {
        UUID value = UUID.fromString("fedcba98-7654-3210-89ab-cdef01234567");

        assertEquals(value, UuidBinary.uuid(UuidBinary.bytes(value)));
        assertThrows(IllegalArgumentException.class, () -> UuidBinary.uuid(new byte[15]));
    }

    @Test
    void lockOrderUsesUnsignedBinaryOrderAndRemovesDuplicates() {
        UUID lowerBinary = new UUID(0L, 0L);
        UUID higherBinary = new UUID(Long.MIN_VALUE, 0L);

        assertEquals(List.of(lowerBinary, higherBinary),
                AccountRepository.lockOrder(List.of(higherBinary, lowerBinary, higherBinary)));
    }

    @Test
    void playerNamesNormalizeWithoutLocaleDependentRules() {
        assertEquals("player_one", AccountNames.normalize("Player_One"));
        assertThrows(IllegalArgumentException.class, () -> AccountNames.normalize("ab"));
        assertThrows(IllegalArgumentException.class, () -> AccountNames.normalize("invalid-name"));
    }
}
