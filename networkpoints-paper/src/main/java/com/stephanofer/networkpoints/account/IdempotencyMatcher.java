package com.stephanofer.networkpoints.account;

import com.stephanofer.networkpoints.api.source.MutationContext;
import com.stephanofer.networkpoints.persistence.OperationRecord;
import com.stephanofer.networkpoints.persistence.OperationType;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

final class IdempotencyMatcher {

    private IdempotencyMatcher() {
    }

    static boolean matches(
            OperationRecord record,
            OperationType type,
            UUID accountId,
            Optional<UUID> counterpartyId,
            BigDecimal requestAmount,
            MutationContext context,
            Optional<String> awardGameId,
            Optional<String> awardServerId) {
        return record.type() == type
                && record.accountId().equals(accountId)
                && record.counterpartyId().equals(counterpartyId)
                && record.requestAmount().compareTo(requestAmount) == 0
                && record.operationId().equals(context.operationId())
                && record.context().source().equals(context.source())
                && record.context().actorId().equals(context.actorId())
                && record.context().sourceReference().equals(context.sourceReference())
                && record.awardGameId().equals(awardGameId)
                && record.awardServerId().equals(awardServerId);
    }
}
