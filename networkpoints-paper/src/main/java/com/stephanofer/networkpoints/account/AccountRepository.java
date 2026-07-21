package com.stephanofer.networkpoints.account;

import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import com.stephanofer.networkpoints.persistence.UuidBinary;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class AccountRepository {

    private static final String COLUMNS = "player_uuid, last_known_name, normalized_name, balance, revision";
    private final String accountsTable;

    public AccountRepository(String accountsTable) {
        this.accountsTable = Objects.requireNonNull(accountsTable, "accountsTable");
    }

    public Optional<AccountRecord> find(Connection connection, UUID playerId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM " + this.accountsTable + " WHERE player_uuid = ?")) {
            statement.setBytes(1, UuidBinary.bytes(playerId));
            try (var results = statement.executeQuery()) {
                return results.next() ? Optional.of(map(results)) : Optional.empty();
            }
        }
    }

    public Optional<AccountRecord> findByName(Connection connection, String name) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM " + this.accountsTable + " WHERE normalized_name = ?")) {
            statement.setString(1, AccountNames.normalize(name));
            try (var results = statement.executeQuery()) {
                return results.next() ? Optional.of(map(results)) : Optional.empty();
            }
        }
    }

    public Map<UUID, AccountRecord> lock(Connection connection, Collection<UUID> playerIds) throws SQLException {
        List<UUID> ordered = lockOrder(playerIds);
        Map<UUID, AccountRecord> accounts = new LinkedHashMap<>();
        for (UUID playerId : ordered) {
            try (var statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM " + this.accountsTable + " WHERE player_uuid = ? FOR UPDATE")) {
                statement.setBytes(1, UuidBinary.bytes(playerId));
                try (var results = statement.executeQuery()) {
                    if (results.next()) {
                        accounts.put(playerId, map(results));
                    }
                }
            }
        }
        return accounts;
    }

    public AccountRecord create(Connection connection, UUID playerId, String name) throws SQLException {
        String validatedName = AccountNames.validate(name);
        claimName(connection, playerId, validatedName);
        try (var statement = connection.prepareStatement(
                "INSERT INTO " + this.accountsTable + " "
                        + "(player_uuid, last_known_name, normalized_name, balance, revision) VALUES (?, ?, ?, 0.00, 0) "
                        + "ON DUPLICATE KEY UPDATE last_known_name = VALUES(last_known_name), "
                        + "normalized_name = VALUES(normalized_name)")) {
            statement.setBytes(1, UuidBinary.bytes(playerId));
            statement.setString(2, validatedName);
            statement.setString(3, AccountNames.normalize(validatedName));
            statement.executeUpdate();
        }
        return find(connection, playerId).orElseThrow(() -> new SQLException("Account upsert did not produce a row"));
    }

    public void updateKnownName(Connection connection, UUID playerId, String name) throws SQLException {
        String validatedName = AccountNames.validate(name);
        claimName(connection, playerId, validatedName);
        try (var statement = connection.prepareStatement(
                "UPDATE " + this.accountsTable + " SET last_known_name = ?, normalized_name = ? WHERE player_uuid = ?")) {
            statement.setString(1, validatedName);
            statement.setString(2, AccountNames.normalize(validatedName));
            statement.setBytes(3, UuidBinary.bytes(playerId));
            statement.executeUpdate();
        }
    }

    private void claimName(Connection connection, UUID playerId, String name) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE " + this.accountsTable + " SET normalized_name = NULL "
                        + "WHERE normalized_name = ? AND player_uuid <> ?")) {
            statement.setString(1, AccountNames.normalize(name));
            statement.setBytes(2, UuidBinary.bytes(playerId));
            statement.executeUpdate();
        }
    }

    public BalanceSnapshot updateBalance(Connection connection, AccountRecord account, BigDecimal balance)
            throws SQLException {
        if (account.snapshot().revision() == Long.MAX_VALUE) {
            throw new SQLException("Account revision exhausted for " + account.playerId());
        }
        try (var statement = connection.prepareStatement(
                "UPDATE " + this.accountsTable + " SET balance = ?, revision = revision + 1 "
                        + "WHERE player_uuid = ? AND revision = ?")) {
            statement.setBigDecimal(1, balance);
            statement.setBytes(2, UuidBinary.bytes(account.playerId()));
            statement.setLong(3, account.snapshot().revision());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Locked account revision changed unexpectedly");
            }
        }
        return new BalanceSnapshot(account.playerId(), balance, account.snapshot().revision() + 1);
    }

    public static List<UUID> lockOrder(Collection<UUID> playerIds) {
        return playerIds.stream().distinct().sorted(UuidBinary.COMPARATOR).toList();
    }

    private static AccountRecord map(ResultSet results) throws SQLException {
        UUID playerId = UuidBinary.uuid(results.getBytes("player_uuid"));
        return new AccountRecord(
                playerId,
                results.getString("last_known_name"),
                Optional.ofNullable(results.getString("normalized_name")),
                new BalanceSnapshot(playerId, results.getBigDecimal("balance"), results.getLong("revision")));
    }
}
