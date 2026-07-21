package com.stephanofer.networkpoints.award;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stephanofer.networkboosters.api.calculation.BoostCalculation;
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
        NetworkBoostersAwardCalculator calculator = new NetworkBoostersAwardCalculator(
                ignored -> false,
                ignored -> { throw new AssertionError("calculation must not run"); });

        assertTrue(calculator.calculate(request("1.00")).isEmpty());
    }

    @Test
    void roundsExactlyOnceFromTheReturnedMultiplier() {
        NetworkBoostersAwardCalculator calculator = new NetworkBoostersAwardCalculator(
                ignored -> true,
                request -> new BoostCalculation(
                        request.baseAmount(), new BigDecimal("1.33333333"),
                        new BigDecimal("999.999"), List.of(), false));

        AwardCalculation result = calculator.calculate(request("0.10")).orElseThrow();

        assertEquals(new BigDecimal("0.13"), result.finalAmount());
        assertEquals(new BigDecimal("1.33333333"), result.multiplier());
    }

    private static AwardRequest request(String amount) {
        UUID playerId = UUID.randomUUID();
        MutationContext context = new MutationContext(
                UUID.randomUUID(), Key.key("test:award"), Optional.empty(), Optional.empty());
        return new AwardRequest(playerId, new BigDecimal(amount), "skywars", "skywars-1", context);
    }
}
