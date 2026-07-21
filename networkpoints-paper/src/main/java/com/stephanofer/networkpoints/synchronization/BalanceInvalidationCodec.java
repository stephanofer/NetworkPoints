package com.stephanofer.networkpoints.synchronization;

import com.stephanofer.networkpoints.persistence.TransactionKind;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class BalanceInvalidationCodec {

    public static final int MAX_PAYLOAD_LENGTH = 512;
    private static final String VERSION = "1";
    private static final Pattern SERVER_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public String encode(BalanceInvalidation invalidation) {
        Objects.requireNonNull(invalidation, "invalidation");
        validateServerId(invalidation.sourceServerId());
        String payload = String.join("|", VERSION, invalidation.operationId().toString(),
                invalidation.sourceServerId(), invalidation.playerId().toString(),
                Long.toString(invalidation.revision()), invalidation.transactionKind().name());
        if (payload.length() > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("invalidation payload exceeds the size limit");
        }
        return payload;
    }

    public BalanceInvalidation decode(String payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.isEmpty() || payload.length() > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("invalidation payload has an invalid size");
        }
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 6 || !VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("unsupported invalidation payload");
        }
        validateServerId(parts[2]);
        try {
            long revision = Long.parseLong(parts[4]);
            return new BalanceInvalidation(UUID.fromString(parts[1]), parts[2], UUID.fromString(parts[3]),
                    revision, TransactionKind.valueOf(parts[5]));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid invalidation payload", exception);
        }
    }

    private static void validateServerId(String serverId) {
        if (!SERVER_ID.matcher(serverId).matches()) {
            throw new IllegalArgumentException("invalid source server ID");
        }
    }
}
