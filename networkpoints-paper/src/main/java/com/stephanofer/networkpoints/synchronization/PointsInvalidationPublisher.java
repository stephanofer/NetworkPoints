package com.stephanofer.networkpoints.synchronization;

import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import com.stephanofer.networkpoints.persistence.TransactionKind;
import java.util.UUID;

@FunctionalInterface
public interface PointsInvalidationPublisher {
    void publish(UUID operationId, TransactionKind kind, BalanceSnapshot snapshot);
}
