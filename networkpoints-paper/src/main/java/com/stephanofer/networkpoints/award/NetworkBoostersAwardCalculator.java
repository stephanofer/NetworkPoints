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
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

public final class NetworkBoostersAwardCalculator implements AwardCalculator {

    private final Predicate<UUID> readiness;
    private final Function<BoostRequest, BoostCalculation> calculation;

    public NetworkBoostersAwardCalculator(NetworkBoostersService boosters) {
        Objects.requireNonNull(boosters, "boosters");
        this.readiness = boosters::isReady;
        this.calculation = boosters::calculate;
    }

    NetworkBoostersAwardCalculator(
            Predicate<UUID> readiness,
            Function<BoostRequest, BoostCalculation> calculation) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.calculation = Objects.requireNonNull(calculation, "calculation");
    }

    @Override
    public Optional<AwardCalculation> calculate(AwardRequest request) {
        Objects.requireNonNull(request, "request");
        if (!this.readiness.test(request.playerId())) {
            return Optional.empty();
        }
        BoostCalculation calculated = this.calculation.apply(BoostRequest.of(
                request.playerId(), BoosterTarget.NETWORK_PROGRESSION_POINTS, request.amount(),
                request.gameId(), request.serverId()));
        if (calculated.baseAmount().compareTo(request.amount()) != 0) {
            throw new IllegalStateException("NetworkBoosters returned a different base amount");
        }
        BigDecimal multiplier = calculated.multiplier();
        BigDecimal finalAmount = request.amount().multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
        return Optional.of(new AwardCalculation(request.amount(), multiplier, finalAmount));
    }
}
