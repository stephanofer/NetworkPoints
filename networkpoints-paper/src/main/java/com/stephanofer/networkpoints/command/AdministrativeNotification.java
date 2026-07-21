package com.stephanofer.networkpoints.command;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record AdministrativeNotification(
        UUID operationId,
        String sourceServerId,
        Operation operation,
        Optional<UUID> actorId,
        String actorName,
        UUID targetId,
        BigDecimal amount,
        BigDecimal balance
) {
    public AdministrativeNotification {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sourceServerId, "sourceServerId");
        Objects.requireNonNull(operation, "operation");
        actorId = Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(actorName, "actorName");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(balance, "balance");
        if (sourceServerId.isBlank() || actorName.isBlank() || actorName.length() > 64
                || actorName.indexOf('|') >= 0 || actorName.chars().anyMatch(Character::isISOControl)
                || amount.signum() < 0 || balance.signum() < 0 || amount.scale() > 2 || balance.scale() > 2) {
            throw new IllegalArgumentException("Invalid administrative notification");
        }
        amount = amount.setScale(2);
        balance = balance.setScale(2);
    }

    public enum Operation {
        GIVE("give"),
        TAKE("take"),
        SET("set"),
        RESET("reset");

        private final String messageKey;

        Operation(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return this.messageKey;
        }
    }
}
