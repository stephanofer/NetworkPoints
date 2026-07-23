package com.stephanofer.networkpoints.api;

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
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;

/**
 * Public NetworkPoints service registered in Paper's services manager.
 *
 * <p>{@link #cachedBalance(UUID)}, {@link #formatAmount(BigDecimal)},
 * {@link #formatAmountPlain(BigDecimal, AmountDisplayMode)}, and {@link #parseAmount(String)} are
 * synchronous, thread-safe, and perform no I/O. Every method returning a future is asynchronous.
 * Future callbacks have no main-thread guarantee, and infrastructure failures complete those
 * futures exceptionally. A normally completed terminal mutation result is durable: repeating the
 * same request and operation ID reproduces the original success or rejection. Retryable statuses
 * do not claim the operation ID.</p>
 */
public interface NetworkPointsService {
    /**
     * Returns the current in-memory snapshot without performing I/O.
     *
     * @param playerId the account identifier
     * @return the cached snapshot, or empty when this server has no cached value
     */
    Optional<BalanceSnapshot> cachedBalance(UUID playerId);

    /**
     * Resolves an account balance asynchronously, using cached data when appropriate.
     *
     * @param playerId the account identifier
     * @return a future containing the resolved snapshot
     */
    CompletableFuture<BalanceSnapshot> balance(UUID playerId);

    /**
     * Reloads an account balance from authoritative storage and updates the cache.
     *
     * @param playerId the account identifier
     * @return a future containing the refreshed snapshot
     */
    CompletableFuture<BalanceSnapshot> refreshBalance(UUID playerId);

    /**
     * Applies a booster-eligible award asynchronously.
     *
     * @param request the validated award request
     * @return a future containing the post-commit business result
     */
    CompletableFuture<MutationResult> award(AwardRequest request);

    /**
     * Applies a direct, non-booster credit asynchronously.
     *
     * @param request the validated credit request
     * @return a future containing the post-commit business result
     */
    CompletableFuture<MutationResult> credit(CreditRequest request);

    /**
     * Applies a debit asynchronously.
     *
     * @param request the validated debit request
     * @return a future containing the post-commit business result
     */
    CompletableFuture<MutationResult> debit(DebitRequest request);

    /**
     * Transfers points atomically between two accounts.
     *
     * @param request the validated transfer request
     * @return a future containing the post-commit business result
     */
    CompletableFuture<TransferResult> transfer(TransferRequest request);

    /**
     * Assigns an absolute balance asynchronously without applying boosters.
     *
     * @param request the validated balance assignment request
     * @return a future containing the post-commit business result
     */
    CompletableFuture<MutationResult> setBalance(SetBalanceRequest request);

    /**
     * Formats an amount as a configured Adventure component.
     *
     * @param amount the non-negative amount to format
     * @return the formatted component
     */
    Component formatAmount(BigDecimal amount);

    /**
     * Formats an amount as plain text in the requested presentation mode.
     *
     * @param amount the non-negative amount to format
     * @param mode the presentation mode
     * @return the formatted plain-text amount
     */
    String formatAmountPlain(BigDecimal amount, AmountDisplayMode mode);

    /**
     * Parses user-facing amount input without throwing for malformed input.
     *
     * @param input the input to parse; null or empty input produces an empty-input failure
     * @return a success containing the normalized amount, or a failure describing the rejection
     */
    AmountParseResult parseAmount(String input);
}
