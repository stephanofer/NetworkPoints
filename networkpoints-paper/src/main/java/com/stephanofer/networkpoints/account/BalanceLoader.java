package com.stephanofer.networkpoints.account;

import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface BalanceLoader {
    CompletableFuture<BalanceSnapshot> load(UUID playerId);
}
