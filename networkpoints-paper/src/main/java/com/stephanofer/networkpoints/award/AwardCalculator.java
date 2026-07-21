package com.stephanofer.networkpoints.award;

import com.stephanofer.networkpoints.api.request.AwardRequest;
import java.util.Optional;

@FunctionalInterface
public interface AwardCalculator {
    Optional<AwardCalculation> calculate(AwardRequest request);
}
