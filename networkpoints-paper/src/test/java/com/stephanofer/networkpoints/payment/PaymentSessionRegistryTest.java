package com.stephanofer.networkpoints.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentSessionRegistryTest {

    @Test
    void consumesSessionExactlyOnce() {
        PaymentSessionRegistry registry = registryAt("2026-07-21T12:00:00Z");
        PaymentSession session = open(registry);

        PaymentSessionRegistry.Claim claim = registry.claim(session.senderId(), session.token());
        assertEquals(PaymentSessionRegistry.Status.CLAIMED, claim.status());
        assertEquals(session.operationId(), claim.session().orElseThrow().operationId());
        assertEquals(PaymentSessionRegistry.Status.MISSING,
                registry.claim(session.senderId(), session.token()).status());
    }

    @Test
    void replacementMakesOldCallbackHarmlessWithoutRemovingNewSession() {
        PaymentSessionRegistry registry = registryAt("2026-07-21T12:00:00Z");
        PaymentSession old = open(registry);
        PaymentSession replacement = open(registry);

        assertFalse(registry.isActive(old.senderId(), old.token()));
        assertTrue(registry.isActive(replacement.senderId(), replacement.token()));
        assertEquals(PaymentSessionRegistry.Status.MISSING,
                registry.claim(old.senderId(), old.token()).status());
        assertEquals(PaymentSessionRegistry.Status.CLAIMED,
                registry.claim(replacement.senderId(), replacement.token()).status());
    }

    @Test
    void exactExpirationIsRejectedAndRemoved() {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T12:00:00Z"));
        PaymentSessionRegistry registry = new PaymentSessionRegistry(clock);
        PaymentSession session = registry.open(sender, recipient, new BigDecimal("10.00"), Duration.ofSeconds(30));
        clock.instant = session.expiresAt();

        assertEquals(PaymentSessionRegistry.Status.EXPIRED,
                registry.claim(sender, session.token()).status());
        assertEquals(PaymentSessionRegistry.Status.MISSING,
                registry.claim(sender, session.token()).status());
    }

    private static PaymentSession open(PaymentSessionRegistry registry) {
        return registry.open(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                new BigDecimal("10.00"), Duration.ofSeconds(30));
    }

    private static PaymentSessionRegistry registryAt(String instant) {
        return new PaymentSessionRegistry(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return this.instant;
        }
    }
}
