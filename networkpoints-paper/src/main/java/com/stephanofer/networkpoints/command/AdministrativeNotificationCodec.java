package com.stephanofer.networkpoints.command;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class AdministrativeNotificationCodec {
    public static final int MAX_PAYLOAD_LENGTH = 512;
    private static final String VERSION = "1";
    private static final Pattern SERVER_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public String encode(AdministrativeNotification notification) {
        Objects.requireNonNull(notification, "notification");
        validateServerId(notification.sourceServerId());
        String payload = String.join("|", VERSION, notification.operationId().toString(),
                notification.sourceServerId(), notification.operation().name(),
                notification.actorId().map(UUID::toString).orElse(""), notification.actorName(),
                notification.targetId().toString(), notification.amount().toPlainString(),
                notification.balance().toPlainString());
        if (payload.length() > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("administrative notification payload exceeds the size limit");
        }
        return payload;
    }

    public AdministrativeNotification decode(String payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.isEmpty() || payload.length() > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("administrative notification payload has an invalid size");
        }
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 9 || !VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("unsupported administrative notification payload");
        }
        validateServerId(parts[2]);
        try {
            Optional<UUID> actorId = parts[4].isEmpty()
                    ? Optional.empty()
                    : Optional.of(UUID.fromString(parts[4]));
            return new AdministrativeNotification(UUID.fromString(parts[1]), parts[2],
                    AdministrativeNotification.Operation.valueOf(parts[3]), actorId, parts[5],
                    UUID.fromString(parts[6]), new BigDecimal(parts[7]), new BigDecimal(parts[8]));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid administrative notification payload", exception);
        }
    }

    private static void validateServerId(String serverId) {
        if (!SERVER_ID.matcher(serverId).matches()) {
            throw new IllegalArgumentException("invalid source server ID");
        }
    }
}
