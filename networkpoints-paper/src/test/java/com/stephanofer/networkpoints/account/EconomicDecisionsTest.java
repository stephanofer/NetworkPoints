package com.stephanofer.networkpoints.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stephanofer.networkpoints.api.result.MutationStatus;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

final class EconomicDecisionsTest {

    private static final BigDecimal MAXIMUM = new BigDecimal("1000.00");

    @Test
    void creditAcceptsExactMaximumAndRejectsOverflow() {
        var accepted = EconomicDecisions.credit(new BigDecimal("900.00"), new BigDecimal("100.00"), MAXIMUM);
        var rejected = EconomicDecisions.credit(new BigDecimal("900.00"), new BigDecimal("100.01"), MAXIMUM);

        assertTrue(accepted.success());
        assertEquals(new BigDecimal("1000.00"), accepted.balanceAfter());
        assertEquals(MutationStatus.BALANCE_LIMIT_EXCEEDED, rejected.status());
        assertFalse(rejected.success());
    }

    @Test
    void debitAcceptsExactBalanceAndNeverOverdraws() {
        var accepted = EconomicDecisions.debit(new BigDecimal("25.00"), new BigDecimal("25.00"));
        var rejected = EconomicDecisions.debit(new BigDecimal("25.00"), new BigDecimal("25.01"));

        assertEquals(new BigDecimal("0.00"), accepted.balanceAfter());
        assertEquals(new BigDecimal("-25.00"), accepted.delta());
        assertEquals(MutationStatus.INSUFFICIENT_FUNDS, rejected.status());
    }

    @Test
    void assigningSameBalanceStillRepresentsAnAuditableMutation() {
        var decision = EconomicDecisions.setBalance(new BigDecimal("12.50"), new BigDecimal("12.50"), MAXIMUM);

        assertTrue(decision.success());
        assertEquals(new BigDecimal("0.00"), decision.delta());
        assertEquals(new BigDecimal("12.50"), decision.balanceAfter());
    }
}
