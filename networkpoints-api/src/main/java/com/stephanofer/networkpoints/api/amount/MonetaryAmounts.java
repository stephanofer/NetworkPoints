package com.stephanofer.networkpoints.api.amount;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Fixed-scale validation shared by immutable public contracts. */
public final class MonetaryAmounts {
    /** Scale used by all point amounts. */
    public static final int SCALE = 2;
    /** Largest absolute point amount representable by the durable {@code DECIMAL(30,2)} domain. */
    public static final BigDecimal MAX_VALUE = new BigDecimal("9999999999999999999999999999.99");
    private static final int MULTIPLIER_PRECISION = 20;
    private static final int MULTIPLIER_SCALE = 8;

    private MonetaryAmounts() {}

    /**
     * Normalizes a non-negative point amount to {@link #SCALE}.
     *
     * @param amount the amount to validate
     * @param name the parameter name used in validation messages
     * @return the normalized amount
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if the amount is negative, requires rounding, or exceeds the durable domain
     */
    public static BigDecimal nonNegative(BigDecimal amount, String name) {
        BigDecimal normalized = exact(amount, name);
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return normalized;
    }

    /**
     * Normalizes a strictly positive point amount to {@link #SCALE}.
     *
     * @param amount the amount to validate
     * @param name the parameter name used in validation messages
     * @return the normalized amount
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if the amount is not positive, requires rounding, or exceeds the durable domain
     */
    public static BigDecimal positive(BigDecimal amount, String name) {
        BigDecimal normalized = exact(amount, name);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return normalized;
    }

    /**
     * Normalizes a signed point amount to {@link #SCALE}.
     *
     * @param amount the amount to validate
     * @param name the parameter name used in validation messages
     * @return the normalized amount
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if the amount requires rounding or exceeds the durable domain
     */
    public static BigDecimal signed(BigDecimal amount, String name) {
        return exact(amount, name);
    }

    /**
     * Validates a positive multiplier that can be stored exactly as {@code DECIMAL(20,8)}.
     *
     * @param value the multiplier to validate
     * @param name the parameter name used in validation messages
     * @return the normalized multiplier
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if the multiplier is not positive or cannot be stored exactly
     */
    public static BigDecimal multiplier(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        BigDecimal normalized;
        try {
            normalized = value.setScale(Math.min(Math.max(value.scale(), 0), MULTIPLIER_SCALE), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " must fit DECIMAL(20,8)", exception);
        }
        int integerDigits = Math.max(normalized.precision() - normalized.scale(), 0);
        if (normalized.precision() > MULTIPLIER_PRECISION
                || integerDigits > MULTIPLIER_PRECISION - MULTIPLIER_SCALE) {
            throw new IllegalArgumentException(name + " must fit DECIMAL(20,8)");
        }
        return normalized;
    }

    private static BigDecimal exact(BigDecimal amount, String name) {
        Objects.requireNonNull(amount, name);
        BigDecimal normalized;
        try {
            normalized = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " must have at most two decimal places", exception);
        }
        if (normalized.abs().compareTo(MAX_VALUE) > 0) {
            throw new IllegalArgumentException(name + " must fit DECIMAL(30,2)");
        }
        return normalized;
    }
}
