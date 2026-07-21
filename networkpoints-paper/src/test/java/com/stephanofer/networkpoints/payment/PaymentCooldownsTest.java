package com.stephanofer.networkpoints.payment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentCooldownsTest {
    @Test
    void permitsOnlyOneAcquisitionWithinWindow() {
        UUID playerId = UUID.randomUUID();
        PaymentCooldowns cooldowns = new PaymentCooldowns(
                Clock.fixed(Instant.parse("2026-07-21T12:00:00Z"), ZoneOffset.UTC));

        assertTrue(cooldowns.tryAcquire(playerId, 500));
        assertFalse(cooldowns.tryAcquire(playerId, 500));
        cooldowns.remove(playerId);
        assertTrue(cooldowns.tryAcquire(playerId, 500));
    }
}
