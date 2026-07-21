package com.stephanofer.networkpoints.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PaymentSession(
        UUID token,
        UUID operationId,
        UUID senderId,
        UUID recipientId,
        BigDecimal amount,
        Instant expiresAt
) {
    public PaymentSession {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (senderId.equals(recipientId) || amount.signum() <= 0) {
            throw new IllegalArgumentException("Payment session requires distinct accounts and a positive amount");
        }
    }
}
