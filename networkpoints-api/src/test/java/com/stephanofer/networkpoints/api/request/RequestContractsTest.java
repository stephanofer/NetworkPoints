package com.stephanofer.networkpoints.api.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.stephanofer.networkpoints.api.source.MutationContext;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

class RequestContractsTest {
    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final MutationContext CONTEXT = new MutationContext(
            UUID.randomUUID(), Key.key("networkpoints:test"), Optional.empty(), Optional.empty());

    @Test
    void positiveRequestsNormalizeAmounts() {
        CreditRequest credit = new CreditRequest(PLAYER_ID, new BigDecimal("10"), CONTEXT);
        DebitRequest debit = new DebitRequest(PLAYER_ID, new BigDecimal("1.2"), CONTEXT);
        AwardRequest award =
                new AwardRequest(PLAYER_ID, new BigDecimal("3"), "skywars", "skywars-1", CONTEXT);

        assertEquals(new BigDecimal("10.00"), credit.amount());
        assertEquals(new BigDecimal("1.20"), debit.amount());
        assertEquals(new BigDecimal("3.00"), award.amount());
        assertEquals("skywars", award.gameId());
        assertEquals("skywars-1", award.serverId());
    }

    @Test
    void positiveRequestsRejectZeroAndExcessPrecision() {
        assertThrows(IllegalArgumentException.class,
                () -> new CreditRequest(PLAYER_ID, BigDecimal.ZERO, CONTEXT));
        assertThrows(IllegalArgumentException.class,
                () -> new DebitRequest(PLAYER_ID, new BigDecimal("1.001"), CONTEXT));
        assertThrows(IllegalArgumentException.class,
                () -> new AwardRequest(PLAYER_ID, BigDecimal.ONE, " ", "server", CONTEXT));
        assertThrows(IllegalArgumentException.class,
                () -> new AwardRequest(PLAYER_ID, BigDecimal.ONE, "a".repeat(65), "server", CONTEXT));
        assertThrows(IllegalArgumentException.class,
                () -> new AwardRequest(PLAYER_ID, BigDecimal.ONE, "juego-ñ", "server", CONTEXT));
    }

    @Test
    void setBalanceAcceptsZeroButRejectsNegativeValues() {
        SetBalanceRequest request = new SetBalanceRequest(PLAYER_ID, BigDecimal.ZERO, CONTEXT);

        assertEquals(new BigDecimal("0.00"), request.amount());
        assertThrows(IllegalArgumentException.class,
                () -> new SetBalanceRequest(PLAYER_ID, new BigDecimal("-0.01"), CONTEXT));
    }

    @Test
    void transferRequiresDistinctAccountsAndPositiveAmount() {
        UUID recipientId = UUID.randomUUID();
        TransferRequest request =
                new TransferRequest(PLAYER_ID, recipientId, new BigDecimal("5"), CONTEXT);

        assertEquals(new BigDecimal("5.00"), request.amount());
        assertThrows(IllegalArgumentException.class,
                () -> new TransferRequest(PLAYER_ID, PLAYER_ID, BigDecimal.ONE, CONTEXT));
        assertThrows(IllegalArgumentException.class,
                () -> new TransferRequest(PLAYER_ID, recipientId, BigDecimal.ZERO, CONTEXT));
    }

    @Test
    void mutationContextRejectsBlankPresentReference() {
        assertThrows(IllegalArgumentException.class, () -> new MutationContext(
                UUID.randomUUID(), Key.key("networkpoints:test"), Optional.empty(), Optional.of(" ")));
    }
}
