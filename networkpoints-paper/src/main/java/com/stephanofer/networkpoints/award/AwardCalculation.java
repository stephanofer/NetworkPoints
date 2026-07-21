package com.stephanofer.networkpoints.award;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.List;

public record AwardCalculation(BigDecimal baseAmount, BigDecimal multiplier, BigDecimal finalAmount,
                               List<AppliedAwardBoost> appliedBoosts) {
    public AwardCalculation {
        Objects.requireNonNull(baseAmount, "baseAmount");
        Objects.requireNonNull(multiplier, "multiplier");
        Objects.requireNonNull(finalAmount, "finalAmount");
        appliedBoosts = List.copyOf(Objects.requireNonNull(appliedBoosts, "appliedBoosts"));
        if (baseAmount.signum() <= 0 || multiplier.signum() <= 0 || finalAmount.signum() <= 0) {
            throw new IllegalArgumentException("award amounts and multiplier must be positive");
        }
    }
}
