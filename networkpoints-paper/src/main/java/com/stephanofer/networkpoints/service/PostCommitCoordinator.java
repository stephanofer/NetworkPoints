package com.stephanofer.networkpoints.service;

import com.stephanofer.networkpoints.account.BalanceCache;
import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import com.stephanofer.networkpoints.api.event.PointsAwardEvent;
import com.stephanofer.networkpoints.api.event.PointsBalanceChangeEvent;
import com.stephanofer.networkpoints.api.event.PointsTransferEvent;
import com.stephanofer.networkpoints.api.result.MutationResult;
import com.stephanofer.networkpoints.api.result.MutationType;
import com.stephanofer.networkpoints.api.result.TransferResult;
import com.stephanofer.networkpoints.persistence.TransactionKind;
import com.stephanofer.networkpoints.synchronization.PointsInvalidationPublisher;
import java.util.Objects;

public final class PostCommitCoordinator {
    private final BalanceCache balances;
    private final PointsInvalidationPublisher invalidations;
    private final PointsEventDispatcher events;

    public PostCommitCoordinator(
            BalanceCache balances,
            PointsInvalidationPublisher invalidations,
            PointsEventDispatcher events) {
        this.balances = Objects.requireNonNull(balances, "balances");
        this.invalidations = Objects.requireNonNull(invalidations, "invalidations");
        this.events = Objects.requireNonNull(events, "events");
    }

    public MutationResult afterMutation(MutationResult result) {
        Objects.requireNonNull(result, "result");
        if (!result.success()) {
            return result;
        }
        BalanceSnapshot after = result.after().orElseThrow();
        this.balances.publish(after);
        if (result.replayed()) {
            return result;
        }

        TransactionKind kind = transactionKind(result.type());
        this.invalidations.publish(result.operationId(), kind, after);
        this.events.dispatch(new PointsBalanceChangeEvent(
                result.operationId(), result.type(), result.before().orElseThrow(), after, result.delta().orElseThrow()));
        if (result.type() == MutationType.AWARD) {
            this.events.dispatch(new PointsAwardEvent(
                    result.operationId(), result.before().orElseThrow(), after,
                    result.baseAmount().orElseThrow(), result.multiplier().orElseThrow(),
                    result.finalAmount().orElseThrow()));
        }
        return result;
    }

    public TransferResult afterTransfer(TransferResult result) {
        Objects.requireNonNull(result, "result");
        if (!result.success()) {
            return result;
        }
        BalanceSnapshot senderAfter = result.senderAfter().orElseThrow();
        BalanceSnapshot recipientAfter = result.recipientAfter().orElseThrow();
        this.balances.publish(senderAfter);
        this.balances.publish(recipientAfter);
        if (result.replayed()) {
            return result;
        }

        this.invalidations.publish(result.operationId(), TransactionKind.TRANSFER_DEBIT, senderAfter);
        this.invalidations.publish(result.operationId(), TransactionKind.TRANSFER_CREDIT, recipientAfter);
        this.events.dispatch(new PointsTransferEvent(
                result.operationId(), result.senderBefore().orElseThrow(), senderAfter,
                result.recipientBefore().orElseThrow(), recipientAfter, result.amount().orElseThrow()));
        return result;
    }

    private static TransactionKind transactionKind(MutationType type) {
        return switch (type) {
            case AWARD -> TransactionKind.AWARD;
            case CREDIT -> TransactionKind.CREDIT;
            case DEBIT -> TransactionKind.DEBIT;
            case SET_BALANCE -> TransactionKind.SET_BALANCE;
        };
    }
}
