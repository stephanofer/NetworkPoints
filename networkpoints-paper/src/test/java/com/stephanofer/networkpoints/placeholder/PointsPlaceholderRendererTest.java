package com.stephanofer.networkpoints.placeholder;

import com.stephanofer.networkpoints.api.NetworkPointsService;
import com.stephanofer.networkpoints.api.amount.AmountDisplayMode;
import com.stephanofer.networkpoints.api.amount.AmountParseResult;
import com.stephanofer.networkpoints.api.balance.BalanceSnapshot;
import com.stephanofer.networkpoints.api.request.AwardRequest;
import com.stephanofer.networkpoints.api.request.CreditRequest;
import com.stephanofer.networkpoints.api.request.DebitRequest;
import com.stephanofer.networkpoints.api.request.SetBalanceRequest;
import com.stephanofer.networkpoints.api.request.TransferRequest;
import com.stephanofer.networkpoints.api.result.MutationResult;
import com.stephanofer.networkpoints.api.result.TransferResult;
import com.stephanofer.networkpoints.config.ConfigSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PointsPlaceholderRendererTest {
    @Test
    void rendersOnlyFromCachedBalance() {
        UUID playerId = UUID.randomUUID();
        FakePoints points = new FakePoints(playerId, new BigDecimal("12500.00"));
        PointsPlaceholderRenderer renderer = new PointsPlaceholderRenderer(points, PointsPlaceholderRendererTest::config);

        assertEquals("12500", renderer.render(playerId, "balance_raw"));
        assertEquals("12,500", renderer.render(playerId, "balance_grouped"));
        assertEquals("12.5K", renderer.render(playerId, "balance_compact"));
        assertEquals("Points", renderer.render(playerId, "currency_name"));
        assertEquals("*", renderer.render(null, "currency_symbol"));
        assertEquals("missing", renderer.render(UUID.randomUUID(), "balance"));
        assertNull(renderer.render(playerId, "unknown"));
        assertEquals(0, points.asyncCalls);
    }

    private static ConfigSnapshot config() {
        ConfigSnapshot.Currency currency = new ConfigSnapshot.Currency("Point", "Points", "*", "<amount> <symbol>");
        ConfigSnapshot.AmountFormat format = new ConfigSnapshot.AmountFormat(ConfigSnapshot.DisplayMode.COMPACT,
                "#,##0.##", ',', '.', List.of());
        ConfigSnapshot.Reloadable reloadable = new ConfigSnapshot.Reloadable(currency,
                new ConfigSnapshot.AmountInput(Map.of("k", new BigDecimal("1000"))), format,
                null, null, null, null, new ConfigSnapshot.Placeholder("missing"), Map.of(), Map.of());
        return new ConfigSnapshot(null, reloadable, List.of());
    }

    private static final class FakePoints implements NetworkPointsService {
        private final UUID playerId;
        private final BigDecimal balance;
        private int asyncCalls;

        private FakePoints(UUID playerId, BigDecimal balance) {
            this.playerId = playerId;
            this.balance = balance;
        }

        @Override
        public Optional<BalanceSnapshot> cachedBalance(UUID playerId) {
            return this.playerId.equals(playerId)
                    ? Optional.of(new BalanceSnapshot(playerId, this.balance, 1)) : Optional.empty();
        }

        @Override
        public CompletableFuture<BalanceSnapshot> balance(UUID playerId) {
            this.asyncCalls++;
            throw new AssertionError("Placeholder performed I/O-capable balance lookup");
        }

        @Override
        public CompletableFuture<BalanceSnapshot> refreshBalance(UUID playerId) {
            this.asyncCalls++;
            throw new AssertionError("Placeholder performed refresh");
        }

        @Override public CompletableFuture<MutationResult> award(AwardRequest request) { throw new AssertionError(); }
        @Override public CompletableFuture<MutationResult> credit(CreditRequest request) { throw new AssertionError(); }
        @Override public CompletableFuture<MutationResult> debit(DebitRequest request) { throw new AssertionError(); }
        @Override public CompletableFuture<TransferResult> transfer(TransferRequest request) { throw new AssertionError(); }
        @Override public CompletableFuture<MutationResult> setBalance(SetBalanceRequest request) { throw new AssertionError(); }

        @Override
        public Component formatAmount(BigDecimal amount) {
            return Component.text(formatAmountPlain(amount, AmountDisplayMode.COMPACT));
        }

        @Override
        public String formatAmountPlain(BigDecimal amount, AmountDisplayMode mode) {
            return switch (mode) {
                case RAW -> "12500";
                case GROUPED -> "12,500";
                case COMPACT -> "12.5K";
            };
        }

        @Override
        public AmountParseResult parseAmount(String input) {
            throw new AssertionError();
        }
    }
}
