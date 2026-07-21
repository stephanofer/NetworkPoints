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
import com.stephanofer.networkpoints.persistence.TransactionRecord;
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
    private final AwardCalculator awards;
    private final BigDecimal maximumBalance;
    private final String serverId;

    public EconomicEngine(
            Database database,
            AccountRepository accounts,
            TransactionRepository transactions,
            AwardCalculator awards,
            BigDecimal maximumBalance,
            String serverId) {
        this.database = Objects.requireNonNull(database, "database");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.awards = Objects.requireNonNull(awards, "awards");
        this.maximumBalance = Objects.requireNonNull(maximumBalance, "maximumBalance");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
    }

    public CompletableFuture<MutationResult> award(AwardRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<MutationResult> attempt = this.database.transaction(MUTATION_OPTIONS,
                connection -> award(connection, request));
        SingleCommand command = new SingleCommand(
                request.playerId(), request.amount(), request.context(), MutationType.AWARD, TransactionKind.AWARD);
        return recoverSingle(command, attempt);
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
        if (account == null) {
            return rejected(command, MutationStatus.ACCOUNT_NOT_FOUND, Optional.empty());
        }

        List<TransactionRecord> existing = this.transactions.findOperation(connection, command.context().operationId());
        if (!existing.isEmpty()) {
            return replaySingle(command, existing);
        }

        EconomicDecisions.Decision decision = switch (command.type()) {
            case CREDIT -> EconomicDecisions.credit(account.snapshot().balance(), command.amount(), this.maximumBalance);
            case DEBIT -> EconomicDecisions.debit(account.snapshot().balance(), command.amount());
            case SET_BALANCE -> EconomicDecisions.setBalance(
                    account.snapshot().balance(), command.amount(), this.maximumBalance);
            default -> throw new IllegalStateException("Unsupported durable mutation type: " + command.type());
        };
        if (!decision.success()) {
            return rejected(command, decision.status(), Optional.of(account.snapshot()));
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
        return successful(command, account.snapshot(), after, decision.delta(), false);
    }

    private MutationResult award(Connection connection, AwardRequest request) throws SQLException {
        SingleCommand command = new SingleCommand(
                request.playerId(), request.amount(), request.context(), MutationType.AWARD, TransactionKind.AWARD);
        AccountRecord account = this.accounts.lock(connection, List.of(request.playerId())).get(request.playerId());
        if (account == null) {
            return rejected(command, MutationStatus.ACCOUNT_NOT_FOUND, Optional.empty());
        }

        List<TransactionRecord> existing = this.transactions.findOperation(connection, request.context().operationId());
        if (!existing.isEmpty()) {
            return replaySingle(command, existing);
        }

        Optional<AwardCalculation> calculated = this.awards.calculate(request);
        if (calculated.isEmpty()) {
            return rejected(command, MutationStatus.BOOSTER_STATE_NOT_READY, Optional.of(account.snapshot()));
        }
        AwardCalculation award = calculated.orElseThrow();
        EconomicDecisions.Decision decision = EconomicDecisions.credit(
                account.snapshot().balance(), award.finalAmount(), this.maximumBalance);
        if (!decision.success()) {
            return rejected(command, decision.status(), Optional.of(account.snapshot()));
        }

        BalanceSnapshot after = this.accounts.updateBalance(connection, account, decision.balanceAfter());
        this.transactions.insert(connection, new TransactionWrite(
                request.context().operationId(), 0, request.playerId(), Optional.empty(), TransactionKind.AWARD,
                decision.delta(), award.baseAmount(), award.multiplier(), account.snapshot(), after,
                request.context(), this.serverId));
        return successfulAward(command, account.snapshot(), after, award, false);
    }

    private TransferResult transfer(Connection connection, TransferRequest request) throws SQLException {
        Map<UUID, AccountRecord> locked = this.accounts.lock(
                connection, List.of(request.senderId(), request.recipientId()));
        AccountRecord sender = locked.get(request.senderId());
        AccountRecord recipient = locked.get(request.recipientId());
        if (sender == null || recipient == null) {
            return rejectedTransfer(request, MutationStatus.ACCOUNT_NOT_FOUND);
        }

        List<TransactionRecord> existing = this.transactions.findOperation(connection, request.context().operationId());
        if (!existing.isEmpty()) {
            return replayTransfer(request, existing);
        }
        if (sender.snapshot().balance().compareTo(request.amount()) < 0) {
            return rejectedTransfer(request, MutationStatus.INSUFFICIENT_FUNDS);
        }
        BigDecimal recipientBalance = recipient.snapshot().balance().add(request.amount());
        if (recipientBalance.compareTo(this.maximumBalance) > 0) {
            return rejectedTransfer(request, MutationStatus.BALANCE_LIMIT_EXCEEDED);
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
        return successfulTransfer(request, sender.snapshot(), senderAfter, recipient.snapshot(), recipientAfter, false);
    }

    private CompletableFuture<MutationResult> recoverSingle(
            SingleCommand command, CompletableFuture<MutationResult> attempt) {
        return attempt.exceptionallyCompose(failure -> this.database.transaction(READ_ONLY,
                        connection -> this.transactions.findOperation(connection, command.context().operationId()))
                .thenCompose(existing -> existing.isEmpty()
                        ? CompletableFuture.failedFuture(failure)
                        : CompletableFuture.completedFuture(replaySingle(command, existing))));
    }

    private CompletableFuture<TransferResult> recoverTransfer(
            TransferRequest request, CompletableFuture<TransferResult> attempt) {
        return attempt.exceptionallyCompose(failure -> this.database.transaction(READ_ONLY,
                        connection -> this.transactions.findOperation(connection, request.context().operationId()))
                .thenCompose(existing -> existing.isEmpty()
                        ? CompletableFuture.failedFuture(failure)
                        : CompletableFuture.completedFuture(replayTransfer(request, existing))));
    }

    private static MutationResult replaySingle(SingleCommand command, List<TransactionRecord> records) {
        if (records.size() != 1 || !compatible(command, records.getFirst())) {
            return rejected(command, MutationStatus.IDEMPOTENCY_CONFLICT, Optional.empty());
        }
        TransactionRecord record = records.getFirst();
        BalanceSnapshot before = new BalanceSnapshot(record.accountId(), record.balanceBefore(), record.revisionBefore());
        BalanceSnapshot after = new BalanceSnapshot(record.accountId(), record.balanceAfter(), record.revisionAfter());
        if (command.type() == MutationType.AWARD) {
            BigDecimal base = record.baseAmount().orElseThrow();
            BigDecimal multiplier = record.multiplier().orElseThrow();
            return successfulAward(command, before, after,
                    new AwardCalculation(base, multiplier, record.delta()), true);
        }
        return successful(command, before, after, record.delta(), true);
    }

    private static TransferResult replayTransfer(TransferRequest request, List<TransactionRecord> records) {
        if (records.size() != 2) {
            return rejectedTransfer(request, MutationStatus.IDEMPOTENCY_CONFLICT);
        }
        TransactionRecord debit = records.get(0);
        TransactionRecord credit = records.get(1);
        if (!compatibleTransfer(request, debit, credit)) {
            return rejectedTransfer(request, MutationStatus.IDEMPOTENCY_CONFLICT);
        }
        return successfulTransfer(
                request,
                new BalanceSnapshot(debit.accountId(), debit.balanceBefore(), debit.revisionBefore()),
                new BalanceSnapshot(debit.accountId(), debit.balanceAfter(), debit.revisionAfter()),
                new BalanceSnapshot(credit.accountId(), credit.balanceBefore(), credit.revisionBefore()),
                new BalanceSnapshot(credit.accountId(), credit.balanceAfter(), credit.revisionAfter()),
                true);
    }

    private static boolean compatible(SingleCommand command, TransactionRecord record) {
        return IdempotencyMatcher.matches(record, 0, command.playerId(), Optional.empty(), command.kind(),
                command.amount(), command.context());
    }

    private static boolean compatibleTransfer(
            TransferRequest request, TransactionRecord debit, TransactionRecord credit) {
        return IdempotencyMatcher.matches(debit, 0, request.senderId(), Optional.of(request.recipientId()),
                        TransactionKind.TRANSFER_DEBIT, request.amount(), request.context())
                && IdempotencyMatcher.matches(credit, 1, request.recipientId(), Optional.of(request.senderId()),
                        TransactionKind.TRANSFER_CREDIT, request.amount(), request.context());
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
            SingleCommand command, MutationStatus status, Optional<BalanceSnapshot> before) {
        return new MutationResult(status, command.type(), command.context().operationId(), command.playerId(),
                before, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);
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

    private static TransferResult rejectedTransfer(TransferRequest request, MutationStatus status) {
        return new TransferResult(status, request.context().operationId(), request.senderId(), request.recipientId(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);
    }

    private record SingleCommand(
            UUID playerId,
            BigDecimal amount,
            MutationContext context,
            MutationType type,
            TransactionKind kind) {
    }
}
