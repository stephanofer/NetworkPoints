package com.stephanofer.networkpoints.account;

import com.stephanofer.networkpoints.api.source.MutationContext;
import com.stephanofer.networkpoints.persistence.TransactionKind;
import com.stephanofer.networkpoints.persistence.TransactionRecord;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

final class IdempotencyMatcher {

    private IdempotencyMatcher() {
    }

    static boolean matches(
            TransactionRecord record,
            int entryIndex,
            UUID accountId,
            Optional<UUID> counterpartyId,
            TransactionKind kind,
            BigDecimal baseAmount,
            MutationContext context) {
        return record.entryIndex() == entryIndex
                && record.kind() == kind
                && record.accountId().equals(accountId)
                && record.counterpartyId().equals(counterpartyId)
                && record.baseAmount().isPresent()
                && record.baseAmount().orElseThrow().compareTo(baseAmount) == 0
                && record.operationId().equals(context.operationId())
                && record.source().equals(context.source().asString())
                && record.actorId().equals(context.actorId())
                && record.sourceReference().equals(context.sourceReference());
    }
}
