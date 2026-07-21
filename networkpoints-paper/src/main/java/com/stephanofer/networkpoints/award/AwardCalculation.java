package com.stephanofer.networkpoints.award;

import java.math.BigDecimal;
import java.util.Objects;

public record AwardCalculation(BigDecimal baseAmount, BigDecimal multiplier, BigDecimal finalAmount) {
    public AwardCalculation {
        Objects.requireNonNull(baseAmount, "baseAmount");
        Objects.requireNonNull(multiplier, "multiplier");
        Objects.requireNonNull(finalAmount, "finalAmount");
        if (baseAmount.signum() <= 0 || multiplier.signum() <= 0 || finalAmount.signum() <= 0) {
            throw new IllegalArgumentException("award amounts and multiplier must be positive");
        }
    }
}
