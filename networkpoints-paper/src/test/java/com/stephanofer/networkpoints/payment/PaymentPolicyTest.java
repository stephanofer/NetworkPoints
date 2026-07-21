package com.stephanofer.networkpoints.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stephanofer.networkpoints.config.ConfigSnapshot;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentPolicyTest {
    private final PaymentPolicy policy = new PaymentPolicy();
    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();

    @Test
    void requiresConfirmationAtAndAboveThresholdOnly() {
        assertFalse(evaluate("9999999.99", true).requiresConfirmation());
        assertTrue(evaluate("10000000.00", true).requiresConfirmation());
        assertTrue(evaluate("10000000.01", true).requiresConfirmation());
    }

    @Test
    void enforcesPaymentLimitsAfterSuffixExpansion() {
        assertEquals(PaymentPolicy.Status.BELOW_MINIMUM, evaluate("0.09", true).status());
        assertEquals(PaymentPolicy.Status.ACCEPTED, evaluate("0.10", true).status());
        assertEquals(PaymentPolicy.Status.ACCEPTED, evaluate("100000000.00", true).status());
        assertEquals(PaymentPolicy.Status.ABOVE_MAXIMUM, evaluate("100000000.01", true).status());
    }

    @Test
    void rejectsSelfAndOfflineRecipientWhenConfigured() {
        ConfigSnapshot.Payments config = config(false);
        assertEquals(PaymentPolicy.Status.SELF_PAYMENT,
                this.policy.evaluate(config, this.sender, this.sender, new BigDecimal("1.00"), true).status());
        assertEquals(PaymentPolicy.Status.OFFLINE_RECIPIENT,
                this.policy.evaluate(config, this.sender, this.recipient, new BigDecimal("1.00"), false).status());
    }

    private PaymentPolicy.Decision evaluate(String amount, boolean online) {
        return this.policy.evaluate(config(true), this.sender, this.recipient, new BigDecimal(amount), online);
    }

    private static ConfigSnapshot.Payments config(boolean allowOffline) {
        return new ConfigSnapshot.Payments(true, allowOffline, new BigDecimal("0.10"),
                new BigDecimal("100000000.00"), 500,
                new ConfigSnapshot.Confirmation(true, new BigDecimal("10000000.00"), 30));
    }
}
