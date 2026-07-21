package com.stephanofer.networkpoints.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class TransactionRepository {

    private static final String COLUMNS = "id, operation_id, entry_index, account_uuid, counterparty_uuid, "
            + "transaction_type, delta, base_amount, multiplier, balance_before, balance_after, "
            + "revision_before, revision_after, actor_uuid, source, source_reference, source_server_id, created_at";

    public List<TransactionRecord> findOperation(Connection connection, UUID operationId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM networkpoints_transactions WHERE operation_id = ? ORDER BY entry_index")) {
            statement.setBytes(1, UuidBinary.bytes(operationId));
            try (var results = statement.executeQuery()) {
                List<TransactionRecord> records = new ArrayList<>(2);
                while (results.next()) {
                    records.add(map(results));
                }
                return List.copyOf(records);
            }
        }
    }

    public void insert(Connection connection, TransactionWrite write) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO networkpoints_transactions (operation_id, entry_index, account_uuid, "
                        + "counterparty_uuid, transaction_type, delta, base_amount, multiplier, balance_before, "
                        + "balance_after, revision_before, revision_after, actor_uuid, source, source_reference, "
                        + "source_server_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setBytes(1, UuidBinary.bytes(write.operationId()));
            statement.setInt(2, write.entryIndex());
            statement.setBytes(3, UuidBinary.bytes(write.accountId()));
            setUuid(statement, 4, write.counterpartyId());
            statement.setString(5, write.kind().name());
            statement.setBigDecimal(6, write.delta());
            statement.setBigDecimal(7, write.baseAmount());
            statement.setBigDecimal(8, write.multiplier());
            statement.setBigDecimal(9, write.before().balance());
            statement.setBigDecimal(10, write.after().balance());
            statement.setLong(11, write.before().revision());
            statement.setLong(12, write.after().revision());
            setUuid(statement, 13, write.context().actorId());
            statement.setString(14, write.context().source().asString());
            if (write.context().sourceReference().isPresent()) {
                statement.setString(15, write.context().sourceReference().orElseThrow());
            } else {
                statement.setNull(15, Types.VARCHAR);
            }
            statement.setString(16, write.sourceServerId());
            statement.executeUpdate();
        }
    }

    public List<TransactionRecord> history(Connection connection, UUID accountId, int limit, int offset)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM networkpoints_transactions WHERE account_uuid = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")) {
            statement.setBytes(1, UuidBinary.bytes(accountId));
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            try (var results = statement.executeQuery()) {
                List<TransactionRecord> records = new ArrayList<>(limit);
                while (results.next()) {
                    records.add(map(results));
                }
                return List.copyOf(records);
            }
        }
    }

    public int deleteBefore(Connection connection, Instant cutoff, int batchSize) throws SQLException {
        try (var statement = connection.prepareStatement(
                "DELETE FROM networkpoints_transactions WHERE created_at < ? ORDER BY created_at LIMIT ?")) {
            statement.setTimestamp(1, java.sql.Timestamp.from(cutoff));
            statement.setInt(2, batchSize);
            return statement.executeUpdate();
        }
    }

    private static TransactionRecord map(ResultSet results) throws SQLException {
        return new TransactionRecord(
                results.getLong("id"),
                UuidBinary.uuid(results.getBytes("operation_id")),
                results.getInt("entry_index"),
                UuidBinary.uuid(results.getBytes("account_uuid")),
                nullableUuid(results.getBytes("counterparty_uuid")),
                TransactionKind.valueOf(results.getString("transaction_type")),
                results.getBigDecimal("delta"),
                Optional.ofNullable(results.getBigDecimal("base_amount")),
                Optional.ofNullable(results.getBigDecimal("multiplier")),
                results.getBigDecimal("balance_before"),
                results.getBigDecimal("balance_after"),
                results.getLong("revision_before"),
                results.getLong("revision_after"),
                nullableUuid(results.getBytes("actor_uuid")),
                results.getString("source"),
                Optional.ofNullable(results.getString("source_reference")),
                results.getString("source_server_id"),
                results.getTimestamp("created_at").toInstant());
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
}
