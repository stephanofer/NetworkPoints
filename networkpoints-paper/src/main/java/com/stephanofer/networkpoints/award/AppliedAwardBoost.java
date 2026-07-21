package com.stephanofer.networkpoints.award;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record AppliedAwardBoost(
        UUID activationId,
        String boosterId,
        String activationGroup,
        BigDecimal multiplier) {

    public AppliedAwardBoost {
        Objects.requireNonNull(activationId, "activationId");
        Objects.requireNonNull(boosterId, "boosterId");
        Objects.requireNonNull(activationGroup, "activationGroup");
        Objects.requireNonNull(multiplier, "multiplier");
        if (boosterId.isBlank() || activationGroup.isBlank() || multiplier.signum() <= 0) {
            throw new IllegalArgumentException("applied boost values must be non-blank and positive");
        }
    }
}
