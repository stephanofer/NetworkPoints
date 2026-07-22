package com.stephanofer.networkpoints.persistence;

import com.hera.craftkit.database.Database;
import com.hera.craftkit.database.TransactionIsolation;
import com.hera.craftkit.database.TransactionOptions;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class OperationStore {
    private static final TransactionOptions READ_ONLY = TransactionOptions.readOnly(TransactionIsolation.READ_COMMITTED);

    private final Database database;
    private final OperationRepository operations;

    public OperationStore(Database database, OperationRepository operations) {
        this.database = Objects.requireNonNull(database, "database");
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    public CompletableFuture<Optional<OperationRecord>> find(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId");
        return this.database.transaction(READ_ONLY, connection -> this.operations.find(connection, operationId));
    }
}
