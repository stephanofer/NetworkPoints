package com.stephanofer.networkpoints.amount;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Objects;

public record CompactTier(BigDecimal threshold, String pattern, String suffix, String display) {

    public CompactTier {
        Objects.requireNonNull(threshold, "threshold");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(suffix, "suffix");
        Objects.requireNonNull(display, "display");
        if (threshold.signum() <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        if (suffix.isEmpty()) {
            throw new IllegalArgumentException("suffix must not be empty");
        }
        if (!display.contains("<amount>")) {
            throw new IllegalArgumentException("display must contain <amount>");
        }
        new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(java.util.Locale.ROOT));
    }
}
