package com.stephanofer.networkpoints.award;

import com.stephanofer.networkpoints.api.request.AwardRequest;
import java.math.BigDecimal;
import java.util.Optional;

public final class NeutralAwardCalculator implements AwardCalculator {
    @Override
    public Optional<AwardCalculation> calculate(AwardRequest request) {
        return Optional.of(new AwardCalculation(request.amount(), BigDecimal.ONE, request.amount()));
    }
}
