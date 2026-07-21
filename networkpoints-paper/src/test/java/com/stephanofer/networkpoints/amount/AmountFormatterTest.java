package com.stephanofer.networkpoints.amount;

import static com.stephanofer.networkpoints.api.amount.AmountDisplayMode.COMPACT;
import static com.stephanofer.networkpoints.api.amount.AmountDisplayMode.GROUPED;
import static com.stephanofer.networkpoints.api.amount.AmountDisplayMode.RAW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class AmountFormatterTest {

    private final AmountFormatter formatter = new AmountFormatter(
            "#,##0.##",
            ',',
            '.',
            List.of(
                    new CompactTier(new BigDecimal("1000"), "0.#", "K", "<green><amount></green>"),
                    new CompactTier(new BigDecimal("1000000"), "0.#", "M", "<yellow><amount>"),
                    new CompactTier(new BigDecimal("1000000000"), "0.#", "B", "<red><amount>"),
                    new CompactTier(new BigDecimal("1000000000000"), "0.##", "T", "<red><amount>"),
                    new CompactTier(new BigDecimal("1000000000000000"), "0.##", "Q", "<red><amount>")
            )
    );

    @Test
    void formatsRawAndGroupedAmounts() {
        assertEquals("0", formatter.format(new BigDecimal("0.00"), RAW));
        assertEquals("12500", formatter.format(new BigDecimal("12500.00"), RAW));
        assertEquals("0.1", formatter.format(new BigDecimal("0.10"), RAW));
        assertEquals("12,500", formatter.format(new BigDecimal("12500.00"), GROUPED));
        assertEquals("12,500.1", formatter.format(new BigDecimal("12500.10"), GROUPED));
        assertThrows(IllegalArgumentException.class, () -> formatter.format(new BigDecimal("12500.125"), GROUPED));
    }

    @Test
    void formatsEveryCompactTierAndFallsBackBelowTheFirst() {
        assertEquals("999.99", formatter.format(new BigDecimal("999.99"), COMPACT));
        assertEquals("1K", formatter.format(new BigDecimal("1000"), COMPACT));
        assertEquals("12.5K", formatter.format(new BigDecimal("12500"), COMPACT));
        assertEquals("2M", formatter.format(new BigDecimal("2000000"), COMPACT));
        assertEquals("3.5B", formatter.format(new BigDecimal("3500000000"), COMPACT));
        assertEquals("1.25T", formatter.format(new BigDecimal("1250000000000"), COMPACT));
        assertEquals("1Q", formatter.format(new BigDecimal("1000000000000000"), COMPACT));
    }

    @Test
    void promotesAfterRoundingInsteadOfRenderingOneThousandOfTheLowerTier() {
        assertEquals("999.9K", formatter.format(new BigDecimal("999949"), COMPACT));
        assertEquals("1.0M", formatter.format(new BigDecimal("999999"), COMPACT));
        assertEquals("1.0B", formatter.format(new BigDecimal("999999999"), COMPACT));
        assertEquals("1.0T", formatter.format(new BigDecimal("999999999999"), COMPACT));
        assertEquals("1.0Q", formatter.format(new BigDecimal("999999999999999"), COMPACT));

        AmountFormatter integerPattern = new AmountFormatter(
                "#,##0", ',', '.', List.of(
                        new CompactTier(new BigDecimal("1000"), "0", "K", "<amount>"),
                        new CompactTier(new BigDecimal("1000000"), "0", "M", "<amount>")
                )
        );
        assertEquals("1M", integerPattern.format(new BigDecimal("999999"), COMPACT));
    }

    @Test
    void honorsConfiguredPatternsAndSeparatorsIndependentlyOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            AmountFormatter configured = new AmountFormatter(
                    "#,##0.00", '.', ',',
                    List.of(new CompactTier(new BigDecimal("1000"), "0.00", " thousand", "<amount>"))
            );
            assertEquals("12.500,10", configured.format(new BigDecimal("12500.1"), GROUPED));
            assertEquals("12,50 thousand", configured.format(new BigDecimal("12500"), COMPACT));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void rejectsAmbiguousOrUnorderedConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new AmountFormatter("#,##0", '.', '.', List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AmountFormatter(
                "#,##0", ',', '.', List.of(
                        new CompactTier(BigDecimal.TEN, "0", "A", "<amount>"),
                        new CompactTier(BigDecimal.ONE, "0", "B", "<amount>")
                )));
        assertThrows(IllegalArgumentException.class, () -> new CompactTier(BigDecimal.ZERO, "0", "K", "<amount>"));
        assertThrows(IllegalArgumentException.class, () -> new CompactTier(BigDecimal.ONE, "0", "", "<amount>"));
        assertThrows(IllegalArgumentException.class, () -> formatter.format(new BigDecimal("-0.01"), RAW));
        assertThrows(IllegalArgumentException.class, () -> formatter.format(new BigDecimal("1.001"), COMPACT));
    }

    @Test
    void formatsTierDisplayAsAColoredComponentWhilePlainModesStayUnstyled() {
        Component compact = formatter.formatComponent(new BigDecimal("1000.00"), COMPACT);
        Component plain = formatter.formatComponent(new BigDecimal("1000.00"), RAW);

        assertEquals("1K", PlainTextComponentSerializer.plainText().serialize(compact));
        assertEquals(NamedTextColor.GREEN, compact.color());
        assertEquals("1000", PlainTextComponentSerializer.plainText().serialize(plain));
        assertEquals(null, plain.color());
    }

    @Test
    void supportsNonDivisibleCompactTierBoundaries() {
        AmountFormatter nonDivisible = new AmountFormatter(
                "#,##0.##", ',', '.', List.of(
                        new CompactTier(new BigDecimal("3"), "0.#", "A", "<amount>"),
                        new CompactTier(new BigDecimal("10"), "0.#", "B", "<amount>")
                )
        );

        assertEquals("3.3A", nonDivisible.format(new BigDecimal("9.99"), COMPACT));
    }

    @Test
    void canBeUsedConcurrentlyWithoutLeakingFormatterState() {
        IntStream.range(0, 1_000).parallel().forEach(index -> {
            assertEquals("1.0M", formatter.format(new BigDecimal("999999"), COMPACT));
            assertEquals("12,500.1", formatter.format(new BigDecimal("12500.10"), GROUPED));
        });
    }
}
