package com.stephanofer.networkpoints.account;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stephanofer.networkpoints.api.source.MutationContext;
import com.stephanofer.networkpoints.persistence.TransactionKind;
import com.stephanofer.networkpoints.persistence.TransactionRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

final class IdempotencyMatcherTest {

    private static final UUID OPERATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void compatibleReplayMatchesEveryPersistedRequestField() {
        MutationContext context = context(Key.key("hera:test"), Optional.of(ACTOR_ID), Optional.of("match-7"));

        assertTrue(IdempotencyMatcher.matches(record(), 0, ACCOUNT_ID, Optional.empty(),
                TransactionKind.CREDIT, new BigDecimal("10.0"), context));
    }

    @Test
    void changedEconomicOrAuditIdentityConflicts() {
        TransactionRecord record = record();

        assertFalse(IdempotencyMatcher.matches(record, 0, ACCOUNT_ID, Optional.empty(),
                TransactionKind.CREDIT, new BigDecimal("10.01"), context(Key.key("hera:test"), Optional.of(ACTOR_ID), Optional.of("match-7"))));
        assertFalse(IdempotencyMatcher.matches(record, 0, ACCOUNT_ID, Optional.empty(),
                TransactionKind.DEBIT, new BigDecimal("10.00"), context(Key.key("hera:test"), Optional.of(ACTOR_ID), Optional.of("match-7"))));
        assertFalse(IdempotencyMatcher.matches(record, 0, ACCOUNT_ID, Optional.empty(),
                TransactionKind.CREDIT, new BigDecimal("10.00"), context(Key.key("hera:other"), Optional.of(ACTOR_ID), Optional.of("match-7"))));
        assertFalse(IdempotencyMatcher.matches(record, 0, ACCOUNT_ID, Optional.empty(),
                TransactionKind.CREDIT, new BigDecimal("10.00"), context(Key.key("hera:test"), Optional.empty(), Optional.of("match-7"))));
    }

    private static TransactionRecord record() {
        return new TransactionRecord(
                1L, OPERATION_ID, 0, ACCOUNT_ID, Optional.empty(), TransactionKind.CREDIT,
                new BigDecimal("10.00"), Optional.of(new BigDecimal("10.00")),
                Optional.of(new BigDecimal("1.00000000")), new BigDecimal("5.00"),
                new BigDecimal("15.00"), 4L, 5L, Optional.of(ACTOR_ID), "hera:test",
                Optional.of("match-7"), "lobby-1", Instant.parse("2026-07-21T00:00:00Z"));
    }

    private static MutationContext context(Key source, Optional<UUID> actor, Optional<String> reference) {
        return new MutationContext(OPERATION_ID, source, actor, reference);
    }
}
