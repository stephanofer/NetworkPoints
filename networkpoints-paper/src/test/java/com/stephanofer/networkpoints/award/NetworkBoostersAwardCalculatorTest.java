package com.stephanofer.networkpoints.award;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stephanofer.networkboosters.api.calculation.BoostCalculation;
import com.stephanofer.networkboosters.api.calculation.AppliedBoost;
import com.stephanofer.networkboosters.api.booster.ActivationGroup;
import com.stephanofer.networkboosters.api.booster.BoosterId;
import com.stephanofer.networkboosters.api.booster.BoosterTarget;
import com.stephanofer.networkpoints.api.request.AwardRequest;
import com.stephanofer.networkpoints.api.source.MutationContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

class NetworkBoostersAwardCalculatorTest {

    @Test
    void notReadyDoesNotGrantNeutralAmountOrInvokeCalculation() {
        NetworkBoostersAwardCalculator calculator = new NetworkBoostersAwardCalculator(ignored -> Optional.empty());

        assertTrue(calculator.calculate(request("1.00")).isEmpty());
    }

    @Test
    void readyWithoutApplicableBoostersIsALegitimateNeutralCalculation() {
        NetworkBoostersAwardCalculator calculator = new NetworkBoostersAwardCalculator(
                request -> Optional.of(BoostCalculation.neutral(request.baseAmount())));

        AwardCalculation result = calculator.calculate(request("10.00")).orElseThrow();

        assertEquals(0, BigDecimal.ONE.compareTo(result.multiplier()));
        assertEquals(new BigDecimal("10.00"), result.finalAmount());
        assertTrue(result.appliedBoosts().isEmpty());
    }

    @Test
    void roundsExactlyOnceFromTheReturnedMultiplier() {
        UUID activationId = UUID.randomUUID();
        NetworkBoostersAwardCalculator calculator = new NetworkBoostersAwardCalculator(
                request -> {
                    assertEquals(BoosterTarget.NETWORK_POINTS, request.target());
                    return Optional.of(new BoostCalculation(
                            request.baseAmount(), new BigDecimal("1.33333333"),
                            new BigDecimal("999.999"), List.of(new AppliedBoost(activationId,
                                    BoosterId.of("weekend"), ActivationGroup.of("global"),
                                    new BigDecimal("1.33333333"))), false));
                });

        AwardCalculation result = calculator.calculate(request("0.10")).orElseThrow();

        assertEquals(new BigDecimal("0.13"), result.finalAmount());
        assertEquals(new BigDecimal("1.33333333"), result.multiplier());
        assertEquals(List.of(new AppliedAwardBoost(activationId, "weekend", "global",
                new BigDecimal("1.33333333"))), result.appliedBoosts());
    }

    private static AwardRequest request(String amount) {
        UUID playerId = UUID.randomUUID();
        MutationContext context = new MutationContext(
                UUID.randomUUID(), Key.key("test:award"), Optional.empty(), Optional.empty());
        return new AwardRequest(playerId, new BigDecimal(amount), "skywars", "skywars-1", context);
    }
}
