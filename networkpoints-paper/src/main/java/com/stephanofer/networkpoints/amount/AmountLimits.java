package com.stephanofer.networkpoints.amount;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public final class AmountLimits {

    public static final int SCALE = 2;
    public static final BigDecimal DECIMAL_30_2_MAX = new BigDecimal("9999999999999999999999999999.99");

    private final BigDecimal maximumBalance;

    public AmountLimits(BigDecimal maximumBalance) {
        Objects.requireNonNull(maximumBalance, "maximumBalance");
        try {
            this.maximumBalance = maximumBalance.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("maximumBalance must have at most two decimal places", exception);
        }
        if (this.maximumBalance.signum() < 0) {
            throw new IllegalArgumentException("maximumBalance must not be negative");
        }
        if (this.maximumBalance.compareTo(DECIMAL_30_2_MAX) > 0) {
            throw new IllegalArgumentException("maximumBalance exceeds DECIMAL(30,2)");
        }
    }

    public BigDecimal normalize(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");

        final BigDecimal normalized;
        try {
            normalized = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("amount must have at most two decimal places", exception);
        }

        if (normalized.signum() < 0 || normalized.compareTo(maximumBalance) > 0) {
            throw new IllegalArgumentException("amount must be between 0.00 and " + maximumBalance.toPlainString());
        }
        return normalized;
    }

    public BigDecimal maximumBalance() {
        return maximumBalance;
    }
}
