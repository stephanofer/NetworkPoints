package com.stephanofer.networkpoints.command;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdministrativeNotificationCodecTest {
    private final AdministrativeNotificationCodec codec = new AdministrativeNotificationCodec();

    @Test
    void roundTripsPlayerAndConsoleActors() {
        AdministrativeNotification player = notification(Optional.of(UUID.randomUUID()), "Administrator");
        AdministrativeNotification console = notification(Optional.empty(), "Console");

        assertEquals(player, this.codec.decode(this.codec.encode(player)));
        assertEquals(console, this.codec.decode(this.codec.encode(console)));
    }

    @Test
    void rejectsMalformedUnsupportedAndUnsafePayloads() {
        assertThrows(IllegalArgumentException.class, () -> this.codec.decode("1|bad"));
        assertThrows(IllegalArgumentException.class, () -> this.codec.decode("x".repeat(513)));
        assertThrows(IllegalArgumentException.class, () -> new AdministrativeNotification(
                UUID.randomUUID(), "lobby-1", AdministrativeNotification.Operation.GIVE, Optional.empty(),
                "Bad|Actor", UUID.randomUUID(), new BigDecimal("1.00"), new BigDecimal("2.00")));
        assertThrows(IllegalArgumentException.class, () -> this.codec.decode(
                "1|" + UUID.randomUUID() + "|INVALID SERVER|GIVE||Console|" + UUID.randomUUID() + "|1.00|2.00"));
    }

    private static AdministrativeNotification notification(Optional<UUID> actorId, String actorName) {
        return new AdministrativeNotification(UUID.randomUUID(), "lobby-1",
                AdministrativeNotification.Operation.TAKE, actorId, actorName, UUID.randomUUID(),
                new BigDecimal("12.50"), new BigDecimal("40.25"));
    }
}
