package com.stephanofer.networkpoints.persistence;

import com.hera.craftkit.database.Database;
import com.hera.craftkit.database.TransactionIsolation;
import com.hera.craftkit.database.TransactionOptions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class AuditStore {

    private static final TransactionOptions READ_ONLY = TransactionOptions.readOnly(TransactionIsolation.READ_COMMITTED);

    private final Database database;
    private final TransactionRepository transactions;
    private final Clock clock;

    public AuditStore(Database database, TransactionRepository transactions, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CompletableFuture<List<TransactionRecord>> history(UUID accountId, int page, int pageSize, int maximumPage) {
        if (page < 1 || page > maximumPage || pageSize < 1) {
            throw new IllegalArgumentException("invalid history page");
        }
        int offset = Math.multiplyExact(page - 1, pageSize);
        return this.database.transaction(READ_ONLY,
                connection -> this.transactions.history(connection, accountId, pageSize, offset));
    }

    public CompletableFuture<Integer> cleanup(int retentionDays, int batchSize) {
        if (retentionDays < 1 || batchSize < 1) {
            throw new IllegalArgumentException("retentionDays and batchSize must be positive");
        }
        Instant cutoff = this.clock.instant().minus(Duration.ofDays(retentionDays));
        return this.database.update(connection -> this.transactions.deleteBefore(connection, cutoff, batchSize));
    }
}
