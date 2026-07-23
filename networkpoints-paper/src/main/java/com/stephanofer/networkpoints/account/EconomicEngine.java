package com.stephanofer.networkpoints.account;

import com.hera.craftkit.database.Database;
import com.hera.craftkit.database.TransactionIsolation;
import com.hera.craftkit.database.TransactionOptions;
import com.hera.craftkit.database.TransactionRetryPolicy;
import com.stephanofer.networkpoints.award.AwardCalculation;
import com.stephanofer.networkpoints.award.AwardCalculator;
import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import com.stephanofer.networkpoints.api.request.AwardRequest;
import com.stephanofer.networkpoints.api.request.CreditRequest;
import com.stephanofer.networkpoints.api.request.DebitRequest;
import com.stephanofer.networkpoints.api.request.SetBalanceRequest;
import com.stephanofer.networkpoints.api.request.TransferRequest;
import com.stephanofer.networkpoints.api.result.MutationResult;
import com.stephanofer.networkpoints.api.result.MutationStatus;
import com.stephanofer.networkpoints.api.result.MutationType;
import com.stephanofer.networkpoints.api.result.TransferResult;
import com.stephanofer.networkpoints.api.source.MutationContext;
import com.stephanofer.networkpoints.persistence.TransactionKind;
import com.stephanofer.networkpoints.persistence.OperationRecord;
import com.stephanofer.networkpoints.persistence.OperationRepository;
import com.stephanofer.networkpoints.persistence.OperationType;
import com.stephanofer.networkpoints.persistence.TransactionRepository;
import com.stephanofer.networkpoints.persistence.TransactionWrite;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class EconomicEngine {

    private static final BigDecimal DIRECT_MULTIPLIER = new BigDecimal("1.00000000");
    private static final TransactionOptions MUTATION_OPTIONS = TransactionOptions.builder()
            .isolation(TransactionIsolation.READ_COMMITTED)
            .retryPolicy(TransactionRetryPolicy.mysqlTransient())
            .build();
    private static final TransactionOptions READ_ONLY = TransactionOptions.readOnly(TransactionIsolation.READ_COMMITTED);

    private final Database database;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final OperationRepository operations;
    private final AwardCalculator awards;
    private final BigDecimal maximumBalance;
    private final String serverId;

    public EconomicEngine(
            Database database,
            AccountRepository accounts,
            TransactionRepository transactions,
            OperationRepository operations,
            AwardCalculator awards,
            BigDecimal maximumBalance,
            String serverId) {
        this.database = Objects.requireNonNull(database, "database");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.awards = Objects.requireNonNull(awards, "awards");
        this.maximumBalance = Objects.requireNonNull(maximumBalance, "maximumBalance");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
    }

    public CompletableFuture<MutationResult> award(AwardRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<MutationResult> attempt = this.database.transaction(MUTATION_OPTIONS,
                connection -> award(connection, request));
        return recoverAward(request, attempt);
    }

    public CompletableFuture<MutationResult> credit(CreditRequest request) {
        Objects.requireNonNull(request, "request");
        SingleCommand command = new SingleCommand(
                request.playerId(), request.amount(), request.context(), MutationType.CREDIT, TransactionKind.CREDIT);
        return executeSingle(command);
    }

    public CompletableFuture<MutationResult> debit(DebitRequest request) {
        Objects.requireNonNull(request, "request");
        SingleCommand command = new SingleCommand(
                request.playerId(), request.amount(), request.context(), MutationType.DEBIT, TransactionKind.DEBIT);
        return executeSingle(command);
    }

    public CompletableFuture<MutationResult> setBalance(SetBalanceRequest request) {
        Objects.requireNonNull(request, "request");
        SingleCommand command = new SingleCommand(request.playerId(), request.amount(), request.context(),
                MutationType.SET_BALANCE, TransactionKind.SET_BALANCE);
        return executeSingle(command);
    }

    public CompletableFuture<TransferResult> transfer(TransferRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<TransferResult> attempt = this.database.transaction(MUTATION_OPTIONS,
                connection -> transfer(connection, request));
        return recoverTransfer(request, attempt);
    }

    private CompletableFuture<MutationResult> executeSingle(SingleCommand command) {
        CompletableFuture<MutationResult> attempt = this.database.transaction(MUTATION_OPTIONS,
                connection -> mutate(connection, command));
        return recoverSingle(command, attempt);
    }

    private MutationResult mutate(Connection connection, SingleCommand command) throws SQLException {
        AccountRecord account = this.accounts.lock(connection, List.of(command.playerId())).get(command.playerId());
        Optional<OperationRecord> existing = this.operations.find(connection, command.context().operationId());
        if (existing.isPresent()) {
            return replaySingle(command, existing.orElseThrow());
        }
        if (account == null) {
            this.operations.insert(connection,
                    rejectedOperation(command, MutationStatus.ACCOUNT_NOT_FOUND, Optional.empty()));
            return rejected(command, MutationStatus.ACCOUNT_NOT_FOUND, Optional.empty(), false);
        }

        EconomicDecisions.Decision decision = switch (command.type()) {
            case CREDIT -> EconomicDecisions.credit(account.snapshot().balance(), command.amount(), this.maximumBalance);
            case DEBIT -> EconomicDecisions.debit(account.snapshot().balance(), command.amount());
            case SET_BALANCE -> EconomicDecisions.setBalance(
                    account.snapshot().balance(), command.amount(), this.maximumBalance);
            default -> throw new IllegalStateException("Unsupported durable mutation type: " + command.type());
        };
        if (!decision.success()) {
            this.operations.insert(connection,
                    rejectedOperation(command, decision.status(), Optional.of(account.snapshot())));
            return rejected(command, decision.status(), Optional.of(account.snapshot()), false);
        }

        BalanceSnapshot after = this.accounts.updateBalance(connection, account, decision.balanceAfter());
        this.transactions.insert(connection, new TransactionWrite(
                command.context().operationId(),
                0,
                command.playerId(),
                Optional.empty(),
                command.kind(),
                decision.delta(),
                command.amount(),
                DIRECT_MULTIPLIER,
                account.snapshot(),
                after,
                command.context(),
                this.serverId));
        this.operations.insert(connection, operation(command, account.snapshot(), after, decision.delta()));
        return successful(command, account.snapshot(), after, decision.delta(), false);
    }

    private MutationResult award(Connection connection, AwardRequest request) throws SQLException {
        SingleCommand command = new SingleCommand(
                request.playerId(), request.amount(), request.context(), MutationType.AWARD, TransactionKind.AWARD);
        AccountRecord account = this.accounts.lock(connection, List.of(request.playerId())).get(request.playerId());
        Optional<OperationRecord> existing = this.operations.find(connection, request.context().operationId());
        if (existing.isPresent()) {
            return replayAward(request, existing.orElseThrow());
        }
        if (account == null) {
            this.operations.insert(connection,
                    rejectedAwardOperation(request, MutationStatus.ACCOUNT_NOT_FOUND, Optional.empty()));
            return rejected(command, MutationStatus.ACCOUNT_NOT_FOUND, Optional.empty(), false);
        }

        Optional<AwardCalculation> calculated = this.awards.calculate(request);
        if (calculated.isEmpty()) {
            return rejected(command, MutationStatus.BOOSTER_STATE_NOT_READY, Optional.of(account.snapshot()), false);
        }
        AwardCalculation award = calculated.orElseThrow();
        EconomicDecisions.Decision decision = EconomicDecisions.credit(
                account.snapshot().balance(), award.finalAmount(), this.maximumBalance);
        if (!decision.success()) {
            this.operations.insert(connection,
                    rejectedAwardOperation(request, decision.status(), Optional.of(account.snapshot())));
            return rejected(command, decision.status(), Optional.of(account.snapshot()), false);
        }

        BalanceSnapshot after = this.accounts.updateBalance(connection, account, decision.balanceAfter());
        this.transactions.insert(connection, new TransactionWrite(
                request.context().operationId(), 0, request.playerId(), Optional.empty(), TransactionKind.AWARD,
                decision.delta(), award.baseAmount(), award.multiplier(), account.snapshot(), after,
                request.context(), this.serverId));
        this.operations.insert(connection, new OperationRecord(
                request.context().operationId(), OperationType.AWARD, MutationStatus.SUCCESS,
                request.playerId(), Optional.empty(),
                request.amount(), request.context(), Optional.of(request.gameId()), Optional.of(request.serverId()),
                Optional.of(account.snapshot()), Optional.of(after), Optional.empty(), Optional.empty(),
                Optional.of(decision.delta()), Optional.of(award.baseAmount()), Optional.of(award.multiplier()),
                Optional.of(award.finalAmount()), award.appliedBoosts()));
        return successfulAward(command, account.snapshot(), after, award, false);
    }

    private TransferResult transfer(Connection connection, TransferRequest request) throws SQLException {
        Map<UUID, AccountRecord> locked = this.accounts.lock(
                connection, List.of(request.senderId(), request.recipientId()));
        AccountRecord sender = locked.get(request.senderId());
        AccountRecord recipient = locked.get(request.recipientId());
        Optional<OperationRecord> existing = this.operations.find(connection, request.context().operationId());
        if (existing.isPresent()) {
            return replayTransfer(request, existing.orElseThrow());
        }
        if (sender == null || recipient == null) {
            this.operations.insert(connection, rejectedTransferOperation(request, MutationStatus.ACCOUNT_NOT_FOUND));
            return rejectedTransfer(request, MutationStatus.ACCOUNT_NOT_FOUND, false);
        }
        if (sender.snapshot().balance().compareTo(request.amount()) < 0) {
            this.operations.insert(connection, rejectedTransferOperation(request, MutationStatus.INSUFFICIENT_FUNDS));
            return rejectedTransfer(request, MutationStatus.INSUFFICIENT_FUNDS, false);
        }
        BigDecimal recipientBalance = recipient.snapshot().balance().add(request.amount());
        if (recipientBalance.compareTo(this.maximumBalance) > 0) {
            this.operations.insert(connection,
                    rejectedTransferOperation(request, MutationStatus.BALANCE_LIMIT_EXCEEDED));
            return rejectedTransfer(request, MutationStatus.BALANCE_LIMIT_EXCEEDED, false);
        }

        BalanceSnapshot senderAfter = this.accounts.updateBalance(
                connection, sender, sender.snapshot().balance().subtract(request.amount()));
        BalanceSnapshot recipientAfter = this.accounts.updateBalance(connection, recipient, recipientBalance);
        this.transactions.insert(connection, new TransactionWrite(
                request.context().operationId(), 0, request.senderId(), Optional.of(request.recipientId()),
                TransactionKind.TRANSFER_DEBIT, request.amount().negate(), request.amount(), DIRECT_MULTIPLIER,
                sender.snapshot(), senderAfter, request.context(), this.serverId));
        this.transactions.insert(connection, new TransactionWrite(
                request.context().operationId(), 1, request.recipientId(), Optional.of(request.senderId()),
                TransactionKind.TRANSFER_CREDIT, request.amount(), request.amount(), DIRECT_MULTIPLIER,
                recipient.snapshot(), recipientAfter, request.context(), this.serverId));
        this.operations.insert(connection, new OperationRecord(
                request.context().operationId(), OperationType.TRANSFER, MutationStatus.SUCCESS, request.senderId(),
                Optional.of(request.recipientId()), request.amount(), request.context(), Optional.empty(),
                Optional.empty(), Optional.of(sender.snapshot()), Optional.of(senderAfter),
                Optional.of(recipient.snapshot()), Optional.of(recipientAfter), Optional.of(request.amount().negate()),
                Optional.of(request.amount()), Optional.of(DIRECT_MULTIPLIER), Optional.of(request.amount()), List.of()));
        return successfulTransfer(request, sender.snapshot(), senderAfter, recipient.snapshot(), recipientAfter, false);
    }

    private CompletableFuture<MutationResult> recoverSingle(
            SingleCommand command, CompletableFuture<MutationResult> attempt) {
        return attempt.exceptionallyCompose(failure -> this.database.transaction(READ_ONLY,
                        connection -> this.operations.find(connection, command.context().operationId()))
                .thenCompose(existing -> existing.isEmpty()
                        ? CompletableFuture.failedFuture(failure)
                        : CompletableFuture.completedFuture(replaySingle(command, existing.orElseThrow()))));
    }

    private CompletableFuture<TransferResult> recoverTransfer(
            TransferRequest request, CompletableFuture<TransferResult> attempt) {
        return attempt.exceptionallyCompose(failure -> this.database.transaction(READ_ONLY,
                        connection -> this.operations.find(connection, request.context().operationId()))
                .thenCompose(existing -> existing.isEmpty()
                        ? CompletableFuture.failedFuture(failure)
                        : CompletableFuture.completedFuture(replayTransfer(request, existing.orElseThrow()))));
    }

    private CompletableFuture<MutationResult> recoverAward(
            AwardRequest request, CompletableFuture<MutationResult> attempt) {
        return attempt.exceptionallyCompose(failure -> this.database.transaction(READ_ONLY,
                        connection -> this.operations.find(connection, request.context().operationId()))
                .thenCompose(existing -> existing.isEmpty()
                        ? CompletableFuture.failedFuture(failure)
                        : CompletableFuture.completedFuture(replayAward(request, existing.orElseThrow()))));
    }

    private static MutationResult replaySingle(SingleCommand command, OperationRecord record) {
        if (!compatible(command, record)) {
            return rejected(command, MutationStatus.IDEMPOTENCY_CONFLICT, Optional.empty(), false);
        }
        if (record.outcomeStatus() != MutationStatus.SUCCESS) {
            return rejected(command, record.outcomeStatus(), record.accountBefore(), true);
        }
        return successful(command, record.accountBefore().orElseThrow(), record.accountAfter().orElseThrow(),
                record.delta().orElseThrow(), true);
    }

    private static MutationResult replayAward(AwardRequest request, OperationRecord record) {
        SingleCommand command = new SingleCommand(
                request.playerId(), request.amount(), request.context(), MutationType.AWARD, TransactionKind.AWARD);
        if (!compatibleAward(request, record)) {
            return rejected(command, MutationStatus.IDEMPOTENCY_CONFLICT, Optional.empty(), false);
        }
        if (record.outcomeStatus() != MutationStatus.SUCCESS) {
            return rejected(command, record.outcomeStatus(), record.accountBefore(), true);
        }
        return successfulAward(command, record.accountBefore().orElseThrow(), record.accountAfter().orElseThrow(),
                new AwardCalculation(record.baseAmount().orElseThrow(), record.multiplier().orElseThrow(),
                        record.finalAmount().orElseThrow(),
                        record.appliedBoosts()), true);
    }

    private static TransferResult replayTransfer(TransferRequest request, OperationRecord record) {
        if (!compatibleTransfer(request, record)) {
            return rejectedTransfer(request, MutationStatus.IDEMPOTENCY_CONFLICT, false);
        }
        if (record.outcomeStatus() != MutationStatus.SUCCESS) {
            return rejectedTransfer(request, record.outcomeStatus(), true);
        }
        return successfulTransfer(
                request,
                record.accountBefore().orElseThrow(),
                record.accountAfter().orElseThrow(),
                record.counterpartyBefore().orElseThrow(),
                record.counterpartyAfter().orElseThrow(),
                true);
    }

    private static boolean compatible(SingleCommand command, OperationRecord record) {
        return IdempotencyMatcher.matches(record, operationType(command.type()), command.playerId(), Optional.empty(),
                command.amount(), command.context(), Optional.empty(), Optional.empty());
    }

    private static boolean compatibleAward(AwardRequest request, OperationRecord record) {
        return IdempotencyMatcher.matches(record, OperationType.AWARD, request.playerId(), Optional.empty(),
                request.amount(), request.context(), Optional.of(request.gameId()), Optional.of(request.serverId()));
    }

    private static boolean compatibleTransfer(TransferRequest request, OperationRecord record) {
        return IdempotencyMatcher.matches(record, OperationType.TRANSFER, request.senderId(),
                Optional.of(request.recipientId()), request.amount(), request.context(), Optional.empty(),
                Optional.empty());
    }

    private static OperationRecord operation(
            SingleCommand command, BalanceSnapshot before, BalanceSnapshot after, BigDecimal delta) {
        BigDecimal finalAmount = command.type() == MutationType.SET_BALANCE ? after.balance() : command.amount();
        return new OperationRecord(command.context().operationId(), operationType(command.type()),
                MutationStatus.SUCCESS, command.playerId(), Optional.empty(), command.amount(), command.context(),
                Optional.empty(), Optional.empty(), Optional.of(before), Optional.of(after), Optional.empty(),
                Optional.empty(), Optional.of(delta), Optional.of(command.amount()), Optional.of(DIRECT_MULTIPLIER),
                Optional.of(finalAmount), List.of());
    }

    private static OperationRecord rejectedOperation(
            SingleCommand command, MutationStatus status, Optional<BalanceSnapshot> before) {
        return new OperationRecord(command.context().operationId(), operationType(command.type()), status,
                command.playerId(), Optional.empty(), command.amount(), command.context(), Optional.empty(),
                Optional.empty(), before, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }

    private static OperationRecord rejectedAwardOperation(
            AwardRequest request, MutationStatus status, Optional<BalanceSnapshot> before) {
        return new OperationRecord(request.context().operationId(), OperationType.AWARD, status, request.playerId(),
                Optional.empty(), request.amount(), request.context(), Optional.of(request.gameId()),
                Optional.of(request.serverId()), before, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }

    private static OperationRecord rejectedTransferOperation(TransferRequest request, MutationStatus status) {
        return new OperationRecord(request.context().operationId(), OperationType.TRANSFER, status,
                request.senderId(), Optional.of(request.recipientId()), request.amount(), request.context(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }

    private static OperationType operationType(MutationType type) {
        return OperationType.valueOf(type.name());
    }

    private static MutationResult successful(
            SingleCommand command, BalanceSnapshot before, BalanceSnapshot after, BigDecimal delta, boolean replayed) {
        return new MutationResult(
                MutationStatus.SUCCESS,
                command.type(),
                command.context().operationId(),
                command.playerId(),
                Optional.of(before),
                Optional.of(after),
                Optional.of(delta),
                Optional.of(command.amount()),
                Optional.of(DIRECT_MULTIPLIER),
                Optional.of(command.type() == MutationType.SET_BALANCE ? after.balance() : command.amount()),
                replayed);
    }

    private static MutationResult successfulAward(
            SingleCommand command,
            BalanceSnapshot before,
            BalanceSnapshot after,
            AwardCalculation award,
            boolean replayed) {
        return new MutationResult(
                MutationStatus.SUCCESS, MutationType.AWARD, command.context().operationId(), command.playerId(),
                Optional.of(before), Optional.of(after), Optional.of(award.finalAmount()),
                Optional.of(award.baseAmount()), Optional.of(award.multiplier()),
                Optional.of(award.finalAmount()), replayed);
    }

    private static MutationResult rejected(
            SingleCommand command, MutationStatus status, Optional<BalanceSnapshot> before, boolean replayed) {
        return new MutationResult(status, command.type(), command.context().operationId(), command.playerId(),
                before, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), replayed);
    }

    private static TransferResult successfulTransfer(
            TransferRequest request,
            BalanceSnapshot senderBefore,
            BalanceSnapshot senderAfter,
            BalanceSnapshot recipientBefore,
            BalanceSnapshot recipientAfter,
            boolean replayed) {
        return new TransferResult(
                MutationStatus.SUCCESS,
                request.context().operationId(),
                request.senderId(),
                request.recipientId(),
                Optional.of(senderBefore),
                Optional.of(senderAfter),
                Optional.of(recipientBefore),
                Optional.of(recipientAfter),
                Optional.of(request.amount()),
                Optional.of(request.amount().negate()),
                Optional.of(request.amount()),
                Optional.of(request.amount()),
                Optional.of(DIRECT_MULTIPLIER),
                Optional.of(request.amount()),
                replayed);
    }

    private static TransferResult rejectedTransfer(
            TransferRequest request, MutationStatus status, boolean replayed) {
        return new TransferResult(status, request.context().operationId(), request.senderId(), request.recipientId(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), replayed);
    }

    private record SingleCommand(
            UUID playerId,
            BigDecimal amount,
            MutationContext context,
            MutationType type,
            TransactionKind kind) {
    }
}
