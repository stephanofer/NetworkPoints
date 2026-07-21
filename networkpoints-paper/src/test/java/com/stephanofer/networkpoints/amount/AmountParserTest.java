package com.stephanofer.networkpoints.amount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.stephanofer.networkpoints.api.amount.AmountParseResult;
import com.stephanofer.networkpoints.api.amount.AmountParseResult.Reason;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AmountParserTest {

    private final AmountParser parser = new AmountParser(
            new BigDecimal("0.10"),
            new BigDecimal("999999999999999.00"),
            new BigDecimal("999999999999999.00"),
            Map.of(
                    "k", new BigDecimal("1000"),
                    "m", new BigDecimal("1000000"),
                    "b", new BigDecimal("1000000000"),
                    "t", new BigDecimal("1000000000000"),
                    "q", new BigDecimal("1000000000000000")
            )
    );

    @Test
    void parsesRawAndCaseInsensitiveSuffixAmounts() {
        assertParses("0.10", "0.10");
        assertParses("10", "10.00");
        assertParses("10k", "10000.00");
        assertParses("1.5K", "1500.00");
        assertParses("0.1k", "100.00");
        assertParses("2M", "2000000.00");
        assertParses("0.1q", "100000000000000.00");
    }

    @Test
    void rejectsInvalidSyntaxWithoutTrimmingOrLocaleRules() {
        String[] invalidFormatInputs = {
                " ", " 1", "1 ", "1 0", "1 k", "1,000", "1,5", "1e3", "1E3",
                ".5", "1.", "+1", "-1", "NaN", "Infinity", "k", "1_k"
        };
        assertFailure(null, Reason.EMPTY);
        assertFailure("", Reason.EMPTY);
        for (String input : invalidFormatInputs) {
            assertFailure(input, Reason.INVALID_FORMAT);
        }
        assertFailure("1.000", Reason.TOO_MANY_DECIMALS);
        assertFailure("1.001k", Reason.TOO_MANY_DECIMALS);
        assertFailure("1kk", Reason.UNKNOWN_SUFFIX);
        assertFailure("1x", Reason.UNKNOWN_SUFFIX);
    }

    @Test
    void validatesTheExpandedAmountAgainstEveryBoundary() {
        assertFailure("0.09", Reason.OUT_OF_RANGE);
        assertParses("0.1", "0.10");
        assertParses("999999999999999", "999999999999999.00");
        assertFailure("1000000000000000", Reason.OUT_OF_RANGE);
        assertFailure("1q", Reason.OUT_OF_RANGE);

        AmountParser operationLimited = new AmountParser(
                new BigDecimal("100.00"),
                new BigDecimal("1000.00"),
                new BigDecimal("5000.00"),
                Map.of("k", new BigDecimal("1000"))
        );
        assertEquals(new AmountParseResult.Failure(Reason.TOO_MANY_DECIMALS), operationLimited.parse("0.099k"));
        assertEquals(new AmountParseResult.Success(new BigDecimal("100.00")), operationLimited.parse("0.1k"));
        assertEquals(new AmountParseResult.Success(new BigDecimal("1000.00")), operationLimited.parse("1k"));
        assertEquals(new AmountParseResult.Failure(Reason.OUT_OF_RANGE), operationLimited.parse("1.01k"));

        AmountParser balanceLimited = new AmountParser(
                BigDecimal.ZERO,
                new BigDecimal("10000"),
                new BigDecimal("999.99"),
                Map.of("k", new BigDecimal("1000"))
        );
        assertEquals(new AmountParseResult.Failure(Reason.OUT_OF_RANGE), balanceLimited.parse("1k"));
    }

    @Test
    void rejectsExpandedFractionsThatCannotUseTheFixedScale() {
        AmountParser fractionalSuffix = new AmountParser(
                BigDecimal.ZERO,
                BigDecimal.TEN,
                BigDecimal.TEN,
                Map.of("x", new BigDecimal("0.001"))
        );
        assertEquals(new AmountParseResult.Failure(Reason.OUT_OF_RANGE), fractionalSuffix.parse("1x"));
    }

    @Test
    void validatesConfigurationAtConstruction() {
        new AmountParser(BigDecimal.ZERO, AmountLimits.DECIMAL_30_2_MAX,
                AmountLimits.DECIMAL_30_2_MAX, Map.of());
        BigDecimal aboveDatabaseLimit = new BigDecimal("10000000000000000000000000000.00");
        assertThrows(IllegalArgumentException.class, () -> new AmountParser(
                BigDecimal.ZERO, aboveDatabaseLimit, aboveDatabaseLimit, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new AmountParser(
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new AmountParser(
                BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.TEN, Map.of("bad!", BigDecimal.ONE)));
        assertThrows(IllegalArgumentException.class, () -> new AmountParser(
                BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.TEN, Map.of("x", BigDecimal.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> new AmountParser(
                BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.TEN, Map.of("K", BigDecimal.ONE, "k", BigDecimal.TEN)));
    }

    private void assertParses(String input, String expected) {
        assertEquals(new AmountParseResult.Success(new BigDecimal(expected)), parser.parse(input));
    }

    private void assertFailure(String input, Reason reason) {
        assertEquals(new AmountParseResult.Failure(reason), parser.parse(input), () -> "input: " + input);
    }
}
