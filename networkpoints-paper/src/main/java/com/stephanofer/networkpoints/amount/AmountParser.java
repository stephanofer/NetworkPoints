package com.stephanofer.networkpoints.amount;

import com.stephanofer.networkpoints.api.amount.AmountParseResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AmountParser {

    private static final Pattern INPUT_PATTERN = Pattern.compile("([0-9]+(?:\\.[0-9]{1,2})?)([A-Za-z]*)");
    private static final Pattern EXCESS_DECIMALS_PATTERN = Pattern.compile("[0-9]+\\.[0-9]{3,}[A-Za-z]*");

    private final BigDecimal minimumAmount;
    private final BigDecimal maximumAmount;
    private final BigDecimal maximumBalance;
    private final Map<String, BigDecimal> suffixes;

    public AmountParser(
            BigDecimal minimumAmount,
            BigDecimal maximumAmount,
            BigDecimal maximumBalance,
            Map<String, BigDecimal> suffixes
    ) {
        this.minimumAmount = normalizeLimit(minimumAmount, "minimumAmount");
        this.maximumAmount = normalizeLimit(maximumAmount, "maximumAmount");
        this.maximumBalance = normalizeLimit(maximumBalance, "maximumBalance");
        if (this.minimumAmount.compareTo(this.maximumAmount) > 0) {
            throw new IllegalArgumentException("minimumAmount must not exceed maximumAmount");
        }

        Map<String, BigDecimal> normalizedSuffixes = new HashMap<>();
        Objects.requireNonNull(suffixes, "suffixes").forEach((suffix, multiplier) -> {
            Objects.requireNonNull(suffix, "suffix");
            Objects.requireNonNull(multiplier, "multiplier");
            if (!suffix.matches("[A-Za-z]+")) {
                throw new IllegalArgumentException("suffix must contain letters only");
            }
            if (multiplier.signum() <= 0) {
                throw new IllegalArgumentException("suffix multiplier must be positive");
            }
            String normalizedSuffix = suffix.toLowerCase(Locale.ROOT);
            if (normalizedSuffixes.putIfAbsent(normalizedSuffix, multiplier) != null) {
                throw new IllegalArgumentException("duplicate suffix: " + suffix);
            }
        });
        this.suffixes = Map.copyOf(normalizedSuffixes);
    }

    public AmountParseResult parse(String input) {
        if (input == null || input.isEmpty()) {
            return new AmountParseResult.Failure(AmountParseResult.Reason.EMPTY);
        }
        if (EXCESS_DECIMALS_PATTERN.matcher(input).matches()) {
            return new AmountParseResult.Failure(AmountParseResult.Reason.TOO_MANY_DECIMALS);
        }

        Matcher matcher = INPUT_PATTERN.matcher(input);
        if (!matcher.matches()) {
            return new AmountParseResult.Failure(AmountParseResult.Reason.INVALID_FORMAT);
        }

        BigDecimal multiplier = BigDecimal.ONE;
        String suffix = matcher.group(2);
        if (!suffix.isEmpty()) {
            multiplier = suffixes.get(suffix.toLowerCase(Locale.ROOT));
            if (multiplier == null) {
                return new AmountParseResult.Failure(AmountParseResult.Reason.UNKNOWN_SUFFIX);
            }
        }

        final BigDecimal expanded;
        try {
            expanded = new BigDecimal(matcher.group(1)).multiply(multiplier)
                    .setScale(AmountLimits.SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            return new AmountParseResult.Failure(AmountParseResult.Reason.OUT_OF_RANGE);
        }

        if (expanded.compareTo(minimumAmount) < 0
                || expanded.compareTo(maximumAmount) > 0
                || expanded.compareTo(maximumBalance) > 0) {
            return new AmountParseResult.Failure(AmountParseResult.Reason.OUT_OF_RANGE);
        }
        return new AmountParseResult.Success(expanded);
    }

    private static BigDecimal normalizeLimit(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        final BigDecimal normalized;
        try {
            normalized = value.setScale(AmountLimits.SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " must have at most two decimal places", exception);
        }
        if (normalized.compareTo(AmountLimits.DECIMAL_30_2_MAX) > 0) {
            throw new IllegalArgumentException(name + " exceeds DECIMAL(30,2)");
        }
        return normalized;
    }
}
