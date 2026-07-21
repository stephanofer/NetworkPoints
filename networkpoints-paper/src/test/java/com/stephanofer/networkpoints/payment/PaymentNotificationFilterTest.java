package com.stephanofer.networkpoints.payment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentNotificationFilterTest {
    @Test
    void deliversAnOperationOnlyOnce() {
        PaymentNotificationFilter filter = new PaymentNotificationFilter();
        UUID operationId = UUID.randomUUID();

        assertTrue(filter.shouldDeliver(operationId));
        assertFalse(filter.shouldDeliver(operationId));
        assertTrue(filter.shouldDeliver(UUID.randomUUID()));
    }
}
