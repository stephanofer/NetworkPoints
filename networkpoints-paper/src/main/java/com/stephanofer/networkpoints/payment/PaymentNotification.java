package com.stephanofer.networkpoints.payment;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record PaymentNotification(
        UUID operationId,
        String sourceServerId,
        UUID senderId,
        String senderLastKnownName,
        UUID recipientId,
        BigDecimal amount
) {
    public PaymentNotification {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sourceServerId, "sourceServerId");
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(senderLastKnownName, "senderLastKnownName");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(amount, "amount");
        if (sourceServerId.isBlank() || senderLastKnownName.isBlank() || senderId.equals(recipientId)
                || amount.signum() <= 0 || amount.scale() > 2) {
            throw new IllegalArgumentException("Invalid payment notification");
        }
        amount = amount.setScale(2);
    }
}
