package com.stephanofer.networkpoints.award;

import com.stephanofer.networkboosters.api.NetworkBoostersService;
import com.stephanofer.networkboosters.api.booster.BoosterTarget;
import com.stephanofer.networkboosters.api.calculation.BoostCalculation;
import com.stephanofer.networkboosters.api.calculation.BoostRequest;
import com.stephanofer.networkpoints.api.request.AwardRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class NetworkBoostersAwardCalculator implements AwardCalculator {

    private final Function<BoostRequest, Optional<BoostCalculation>> calculation;

    public NetworkBoostersAwardCalculator(NetworkBoostersService boosters) {
        Objects.requireNonNull(boosters, "boosters");
        this.calculation = boosters::calculateIfReady;
    }

    NetworkBoostersAwardCalculator(Function<BoostRequest, Optional<BoostCalculation>> calculation) {
        this.calculation = Objects.requireNonNull(calculation, "calculation");
    }

    @Override
    public Optional<AwardCalculation> calculate(AwardRequest request) {
        Objects.requireNonNull(request, "request");
        Optional<BoostCalculation> result = this.calculation.apply(BoostRequest.of(
                request.playerId(), BoosterTarget.NETWORK_POINTS, request.amount(), request.gameId(), request.serverId()));
        if (result.isEmpty()) {
            return Optional.empty();
        }
        BoostCalculation calculated = result.orElseThrow();
        if (calculated.baseAmount().compareTo(request.amount()) != 0) {
            throw new IllegalStateException("NetworkBoosters returned a different base amount");
        }
        BigDecimal multiplier = calculated.multiplier();
        BigDecimal finalAmount = request.amount().multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
        var appliedBoosts = calculated.appliedBoosts().stream()
                .map(boost -> new AppliedAwardBoost(boost.activationId(), boost.boosterId().value(),
                        boost.activationGroup().value(), boost.multiplier()))
                .toList();
        return Optional.of(new AwardCalculation(request.amount(), multiplier, finalAmount, appliedBoosts));
    }
}
