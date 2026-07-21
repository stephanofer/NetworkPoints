package com.stephanofer.networkpoints.account;

import com.hera.craftkit.database.Database;
import com.hera.craftkit.database.TransactionIsolation;
import com.hera.craftkit.database.TransactionOptions;
import com.hera.craftkit.database.TransactionRetryPolicy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class AccountStore {

    private static final TransactionOptions READ_ONLY = TransactionOptions.readOnly(TransactionIsolation.READ_COMMITTED);
    private static final TransactionOptions WRITE = TransactionOptions.builder()
            .isolation(TransactionIsolation.READ_COMMITTED)
            .retryPolicy(TransactionRetryPolicy.mysqlTransient())
            .build();

    private final Database database;
    private final AccountRepository accounts;

    public AccountStore(Database database, AccountRepository accounts) {
        this.database = database;
        this.accounts = accounts;
    }

    public CompletableFuture<Optional<AccountRecord>> find(UUID playerId) {
        return this.database.transaction(READ_ONLY, connection -> this.accounts.find(connection, playerId));
    }

    public CompletableFuture<Optional<AccountRecord>> findByName(String name) {
        return this.database.transaction(READ_ONLY, connection -> this.accounts.findByName(connection, name));
    }

    public CompletableFuture<AccountRecord> ensureAccount(UUID playerId, String name) {
        return this.database.transaction(WRITE, connection -> {
            var locked = this.accounts.lock(connection, java.util.List.of(playerId)).get(playerId);
            if (locked == null) {
                return this.accounts.create(connection, playerId, name);
            }
            String normalized = AccountNames.normalize(name);
            if (!locked.lastKnownName().equals(name) || !locked.normalizedName().equals(Optional.of(normalized))) {
                this.accounts.updateKnownName(connection, playerId, name);
                return new AccountRecord(playerId, name, Optional.of(normalized), locked.snapshot());
            }
            return locked;
        });
    }
}
