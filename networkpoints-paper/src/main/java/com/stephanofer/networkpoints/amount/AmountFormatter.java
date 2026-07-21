package com.stephanofer.networkpoints.amount;

import com.stephanofer.networkpoints.api.amount.AmountDisplayMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class AmountFormatter {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final String groupedPattern;
    private final char groupingSeparator;
    private final char decimalSeparator;
    private final List<CompactTier> compactTiers;

    public AmountFormatter(
            String groupedPattern,
            char groupingSeparator,
            char decimalSeparator,
            List<CompactTier> compactTiers
    ) {
        this.groupedPattern = Objects.requireNonNull(groupedPattern, "groupedPattern");
        this.groupingSeparator = groupingSeparator;
        this.decimalSeparator = decimalSeparator;
        if (groupingSeparator == decimalSeparator) {
            throw new IllegalArgumentException("grouping and decimal separators must differ");
        }
        decimalFormat(groupedPattern);

        this.compactTiers = List.copyOf(Objects.requireNonNull(compactTiers, "compactTiers"));
        for (int index = 1; index < this.compactTiers.size(); index++) {
            if (this.compactTiers.get(index - 1).threshold().compareTo(this.compactTiers.get(index).threshold()) >= 0) {
                throw new IllegalArgumentException("compact tier thresholds must be strictly ascending");
            }
        }
    }

    public String format(BigDecimal amount, AmountDisplayMode mode) {
        Objects.requireNonNull(mode, "mode");
        validateAmount(amount);
        return switch (mode) {
            case RAW -> raw(amount);
            case GROUPED -> decimalFormat(groupedPattern).format(amount);
            case COMPACT -> compact(amount);
        };
    }

    public Component formatComponent(BigDecimal amount, AmountDisplayMode mode) {
        Objects.requireNonNull(mode, "mode");
        validateAmount(amount);
        if (mode != AmountDisplayMode.COMPACT) {
            return Component.text(format(amount, mode));
        }

        CompactValue compact = compactValue(amount);
        if (compact.tier() == null) {
            return Component.text(compact.formatted());
        }
        return MINI_MESSAGE.deserialize(
                compact.tier().display().replace("<amount>", "<amount><suffix>"),
                Placeholder.component("amount", Component.text(compact.formatted())),
                Placeholder.unparsed("suffix", compact.tier().suffix())
        );
    }

    private String raw(BigDecimal amount) {
        if (amount.signum() == 0) {
            return "0";
        }
        return amount.stripTrailingZeros().toPlainString();
    }

    private String compact(BigDecimal amount) {
        CompactValue value = compactValue(amount);
        return value.formatted() + (value.tier() == null ? "" : value.tier().suffix());
    }

    private CompactValue compactValue(BigDecimal amount) {
        int tierIndex = -1;
        for (int index = 0; index < compactTiers.size(); index++) {
            if (amount.compareTo(compactTiers.get(index).threshold()) < 0) {
                break;
            }
            tierIndex = index;
        }
        if (tierIndex < 0) {
            return new CompactValue(decimalFormat(groupedPattern).format(amount), null);
        }

        boolean promoted = false;
        CompactTier tier = compactTiers.get(tierIndex);
        BigDecimal reduced = amount.divide(tier.threshold(), MathContext.DECIMAL128);
        while (tierIndex + 1 < compactTiers.size()) {
            DecimalFormat formatter = decimalFormat(tier.pattern());
            BigDecimal rounded = reduced.setScale(formatter.getMaximumFractionDigits(), RoundingMode.HALF_UP);
            BigDecimal nextBoundary = compactTiers.get(tierIndex + 1).threshold().divide(tier.threshold(), MathContext.DECIMAL128);
            if (rounded.compareTo(nextBoundary) < 0) {
                break;
            }
            tier = compactTiers.get(++tierIndex);
            reduced = amount.divide(tier.threshold(), MathContext.DECIMAL128);
            promoted = true;
        }

        DecimalFormat formatter = decimalFormat(tier.pattern());
        String formatted = formatter.format(reduced);
        if (promoted && formatter.getMaximumFractionDigits() > 0 && formatted.indexOf(decimalSeparator) < 0) {
            formatted += decimalSeparator + "0";
        }
        return new CompactValue(formatted, tier);
    }

    private static void validateAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        try {
            amount.setScale(AmountLimits.SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("amount must have at most two decimal places", exception);
        }
    }

    private record CompactValue(String formatted, CompactTier tier) {
    }

    private DecimalFormat decimalFormat(String pattern) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ROOT);
        symbols.setGroupingSeparator(groupingSeparator);
        symbols.setDecimalSeparator(decimalSeparator);
        DecimalFormat formatter = new DecimalFormat(pattern, symbols);
        formatter.setParseBigDecimal(true);
        formatter.setRoundingMode(RoundingMode.HALF_UP);
        return formatter;
    }
}
