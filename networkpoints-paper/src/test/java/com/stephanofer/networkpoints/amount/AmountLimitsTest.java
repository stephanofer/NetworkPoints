package com.stephanofer.networkpoints.amount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AmountLimitsTest {

    private final AmountLimits limits = new AmountLimits(new BigDecimal("999999999999999.00"));

    @Test
    void normalizesEveryValidAmountToFixedScale() {
        assertEquals(new BigDecimal("0.00"), limits.normalize(BigDecimal.ZERO));
        assertEquals(new BigDecimal("0.10"), limits.normalize(new BigDecimal("0.1")));
        assertEquals(new BigDecimal("1.50"), limits.normalize(new BigDecimal("1.500")));
        assertEquals(new BigDecimal("999999999999999.00"), limits.normalize(new BigDecimal("999999999999999")));
    }

    @Test
    void rejectsAmountsOutsideTheDomain() {
        assertThrows(IllegalArgumentException.class, () -> limits.normalize(new BigDecimal("-0.01")));
        assertThrows(IllegalArgumentException.class, () -> limits.normalize(new BigDecimal("0.001")));
        assertThrows(IllegalArgumentException.class, () -> limits.normalize(new BigDecimal("999999999999999.01")));
        assertThrows(NullPointerException.class, () -> limits.normalize(null));
    }

    @Test
    void rejectsInvalidMaximumBalance() {
        assertEquals(AmountLimits.DECIMAL_30_2_MAX,
                new AmountLimits(AmountLimits.DECIMAL_30_2_MAX).maximumBalance());
        assertThrows(IllegalArgumentException.class, () -> new AmountLimits(new BigDecimal("10000000000000000000000000000.00")));
        assertThrows(IllegalArgumentException.class, () -> new AmountLimits(new BigDecimal("-0.01")));
        assertThrows(IllegalArgumentException.class, () -> new AmountLimits(new BigDecimal("1.001")));
        assertThrows(NullPointerException.class, () -> new AmountLimits(null));
    }
}
