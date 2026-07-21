package com.stephanofer.networkpoints.payment;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class PaymentNotificationCodec {
    public static final int MAX_PAYLOAD_LENGTH = 512;
    private static final String VERSION = "1";
    private static final Pattern SERVER_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public String encode(PaymentNotification notification) {
        Objects.requireNonNull(notification, "notification");
        validateServerId(notification.sourceServerId());
        String payload = String.join("|", VERSION, notification.operationId().toString(),
                notification.sourceServerId(), notification.senderId().toString(),
                notification.recipientId().toString(), notification.amount().toPlainString());
        if (payload.length() > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("payment notification payload exceeds the size limit");
        }
        return payload;
    }

    public PaymentNotification decode(String payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.isEmpty() || payload.length() > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("payment notification payload has an invalid size");
        }
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 6 || !VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("unsupported payment notification payload");
        }
        validateServerId(parts[2]);
        try {
            return new PaymentNotification(UUID.fromString(parts[1]), parts[2], UUID.fromString(parts[3]),
                    UUID.fromString(parts[4]), new BigDecimal(parts[5]));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid payment notification payload", exception);
        }
    }

    private static void validateServerId(String serverId) {
        if (!SERVER_ID.matcher(serverId).matches()) {
            throw new IllegalArgumentException("invalid source server ID");
        }
    }
}
