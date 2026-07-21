package com.stephanofer.networkpoints.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentNotificationCodecTest {
    private final PaymentNotificationCodec codec = new PaymentNotificationCodec();

    @Test
    void roundTripsStrictVersionedPayload() {
        PaymentNotification notification = new PaymentNotification(
                UUID.randomUUID(), "lobby-1", UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("12.50"));

        assertEquals(notification, this.codec.decode(this.codec.encode(notification)));
    }

    @Test
    void rejectsMalformedUnsupportedAndOversizedPayloads() {
        assertThrows(IllegalArgumentException.class, () -> this.codec.decode("2|bad"));
        assertThrows(IllegalArgumentException.class, () -> this.codec.decode("x".repeat(513)));
        assertThrows(IllegalArgumentException.class, () -> this.codec.decode(
                "1|" + UUID.randomUUID() + "|INVALID SERVER|" + UUID.randomUUID() + "|"
                        + UUID.randomUUID() + "|1.00"));
    }
}
