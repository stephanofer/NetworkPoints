package com.stephanofer.networkpoints.persistence;

import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import com.stephanofer.networkpoints.api.source.MutationContext;
import com.stephanofer.networkpoints.award.AppliedAwardBoost;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.key.Key;

public final class OperationRepository {
    private final String operationsTable;
    private final String operationBoostersTable;

    public OperationRepository(String operationsTable, String operationBoostersTable) {
        this.operationsTable = Objects.requireNonNull(operationsTable, "operationsTable");
        this.operationBoostersTable = Objects.requireNonNull(operationBoostersTable, "operationBoostersTable");
    }

    public Optional<OperationRecord> find(Connection connection, UUID operationId) throws SQLException {
        OperationRecord operation;
        try (var statement = connection.prepareStatement(
                "SELECT * FROM " + this.operationsTable + " WHERE operation_id = ?")) {
            statement.setBytes(1, UuidBinary.bytes(operationId));
            try (var results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                operation = map(results, java.util.List.of());
            }
        }
        return Optional.of(operation.withAppliedBoosts(appliedBoosts(connection, operationId)));
    }

    public void insert(Connection connection, OperationRecord operation) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO " + this.operationsTable + " (operation_id, mutation_type, account_uuid, "
                        + "counterparty_uuid, request_amount, source, actor_uuid, source_reference, award_game_id, "
                        + "award_server_id, account_balance_before, account_balance_after, account_revision_before, "
                        + "account_revision_after, counterparty_balance_before, counterparty_balance_after, "
                        + "counterparty_revision_before, counterparty_revision_after, delta, base_amount, multiplier, "
                        + "final_amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setBytes(1, UuidBinary.bytes(operation.operationId()));
            statement.setString(2, operation.type().name());
            statement.setBytes(3, UuidBinary.bytes(operation.accountId()));
            setUuid(statement, 4, operation.counterpartyId());
            statement.setBigDecimal(5, operation.requestAmount());
            statement.setString(6, operation.context().source().asString());
            setUuid(statement, 7, operation.context().actorId());
            setString(statement, 8, operation.context().sourceReference());
            setString(statement, 9, operation.awardGameId());
            setString(statement, 10, operation.awardServerId());
            statement.setBigDecimal(11, operation.accountBefore().balance());
            statement.setBigDecimal(12, operation.accountAfter().balance());
            statement.setLong(13, operation.accountBefore().revision());
            statement.setLong(14, operation.accountAfter().revision());
            setSnapshot(statement, 15, 17, operation.counterpartyBefore());
            setSnapshot(statement, 16, 18, operation.counterpartyAfter());
            statement.setBigDecimal(19, operation.delta());
            statement.setBigDecimal(20, operation.baseAmount());
            statement.setBigDecimal(21, operation.multiplier());
            statement.setBigDecimal(22, operation.finalAmount());
            statement.executeUpdate();
        }
        insertAppliedBoosts(connection, operation);
    }

    private static OperationRecord map(ResultSet results, java.util.List<AppliedAwardBoost> appliedBoosts)
            throws SQLException {
        UUID operationId = UuidBinary.uuid(results.getBytes("operation_id"));
        UUID accountId = UuidBinary.uuid(results.getBytes("account_uuid"));
        Optional<UUID> counterpartyId = nullableUuid(results.getBytes("counterparty_uuid"));
        MutationContext context = new MutationContext(operationId, Key.key(results.getString("source")),
                nullableUuid(results.getBytes("actor_uuid")),
                Optional.ofNullable(results.getString("source_reference")));
        return new OperationRecord(
                operationId,
                OperationType.valueOf(results.getString("mutation_type")),
                accountId,
                counterpartyId,
                results.getBigDecimal("request_amount"),
                context,
                Optional.ofNullable(results.getString("award_game_id")),
                Optional.ofNullable(results.getString("award_server_id")),
                snapshot(results, accountId, "account_balance_before", "account_revision_before"),
                snapshot(results, accountId, "account_balance_after", "account_revision_after"),
                optionalSnapshot(results, counterpartyId, "counterparty_balance_before", "counterparty_revision_before"),
                optionalSnapshot(results, counterpartyId, "counterparty_balance_after", "counterparty_revision_after"),
                results.getBigDecimal("delta"),
                results.getBigDecimal("base_amount"),
                results.getBigDecimal("multiplier"),
                results.getBigDecimal("final_amount"),
                appliedBoosts);
    }

    private java.util.List<AppliedAwardBoost> appliedBoosts(Connection connection, UUID operationId)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT activation_id, booster_id, activation_group, multiplier "
                        + "FROM " + this.operationBoostersTable + " WHERE operation_id = ? ORDER BY entry_index")) {
            statement.setBytes(1, UuidBinary.bytes(operationId));
            try (var results = statement.executeQuery()) {
                var boosts = new java.util.ArrayList<AppliedAwardBoost>();
                while (results.next()) {
                    boosts.add(new AppliedAwardBoost(
                            UuidBinary.uuid(results.getBytes("activation_id")),
                            results.getString("booster_id"),
                            results.getString("activation_group"),
                            results.getBigDecimal("multiplier")));
                }
                return java.util.List.copyOf(boosts);
            }
        }
    }

    private void insertAppliedBoosts(Connection connection, OperationRecord operation) throws SQLException {
        if (operation.appliedBoosts().isEmpty()) {
            return;
        }
        try (var statement = connection.prepareStatement(
                "INSERT INTO " + this.operationBoostersTable + " (operation_id, entry_index, activation_id, booster_id, "
                        + "activation_group, multiplier) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (int index = 0; index < operation.appliedBoosts().size(); index++) {
                AppliedAwardBoost boost = operation.appliedBoosts().get(index);
                statement.setBytes(1, UuidBinary.bytes(operation.operationId()));
                statement.setInt(2, index);
                statement.setBytes(3, UuidBinary.bytes(boost.activationId()));
                statement.setString(4, boost.boosterId());
                statement.setString(5, boost.activationGroup());
                statement.setBigDecimal(6, boost.multiplier());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static BalanceSnapshot snapshot(ResultSet results, UUID playerId, String balance, String revision)
            throws SQLException {
        return new BalanceSnapshot(playerId, results.getBigDecimal(balance), results.getLong(revision));
    }

    private static Optional<BalanceSnapshot> optionalSnapshot(
            ResultSet results, Optional<UUID> playerId, String balance, String revision) throws SQLException {
        var value = results.getBigDecimal(balance);
        return value == null ? Optional.empty()
                : Optional.of(new BalanceSnapshot(playerId.orElseThrow(), value, results.getLong(revision)));
    }

    private static void setSnapshot(java.sql.PreparedStatement statement, int balanceIndex, int revisionIndex,
                                    Optional<BalanceSnapshot> snapshot) throws SQLException {
        if (snapshot.isPresent()) {
            statement.setBigDecimal(balanceIndex, snapshot.orElseThrow().balance());
            statement.setLong(revisionIndex, snapshot.orElseThrow().revision());
        } else {
            statement.setNull(balanceIndex, Types.DECIMAL);
            statement.setNull(revisionIndex, Types.BIGINT);
        }
    }

    private static Optional<UUID> nullableUuid(byte[] bytes) {
        return bytes == null ? Optional.empty() : Optional.of(UuidBinary.uuid(bytes));
    }

    private static void setUuid(java.sql.PreparedStatement statement, int index, Optional<UUID> value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setBytes(index, UuidBinary.bytes(value.orElseThrow()));
        } else {
            statement.setNull(index, Types.BINARY);
        }
    }

    private static void setString(java.sql.PreparedStatement statement, int index, Optional<String> value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.orElseThrow());
        } else {
            statement.setNull(index, Types.VARCHAR);
        }
    }
}
