package com.stephanofer.networkpoints.payment;

import com.stephanofer.networkpoints.config.ConfigSnapshot;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public final class PaymentPolicy {

    public Decision evaluate(ConfigSnapshot.Payments config, UUID senderId, UUID recipientId,
                             BigDecimal amount, boolean recipientOnline) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(amount, "amount");
        if (!config.enabled()) {
            return new Decision(Status.DISABLED, false);
        }
        if (senderId.equals(recipientId)) {
            return new Decision(Status.SELF_PAYMENT, false);
        }
        if (!recipientOnline && !config.allowOfflineRecipients()) {
            return new Decision(Status.OFFLINE_RECIPIENT, false);
        }
        if (amount.compareTo(config.minimumAmount()) < 0) {
            return new Decision(Status.BELOW_MINIMUM, false);
        }
        if (amount.compareTo(config.maximumAmount()) > 0) {
            return new Decision(Status.ABOVE_MAXIMUM, false);
        }
        boolean confirmation = config.confirmation().enabled()
                && amount.compareTo(config.confirmation().minimumAmount()) >= 0;
        return new Decision(Status.ACCEPTED, confirmation);
    }

    public enum Status {
        ACCEPTED,
        DISABLED,
        SELF_PAYMENT,
        OFFLINE_RECIPIENT,
        BELOW_MINIMUM,
        ABOVE_MAXIMUM
    }

    public record Decision(Status status, boolean requiresConfirmation) {
        public Decision {
            Objects.requireNonNull(status, "status");
            if (status != Status.ACCEPTED && requiresConfirmation) {
                throw new IllegalArgumentException("Only an accepted payment can require confirmation");
            }
        }

        public boolean accepted() {
            return this.status == Status.ACCEPTED;
        }
    }
}
