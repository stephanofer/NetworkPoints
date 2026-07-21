package com.stephanofer.networkpoints.service;

import com.stephanofer.networkpoints.account.AccountStore;
import com.stephanofer.networkpoints.account.EconomicEngine;
import com.stephanofer.networkpoints.amount.AmountFormatter;
import com.stephanofer.networkpoints.amount.AmountParser;
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
import com.stephanofer.networkpoints.api.result.MutationStatus;
import com.stephanofer.networkpoints.api.result.MutationType;
import com.stephanofer.networkpoints.api.result.TransferResult;
import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;

public final class DurableNetworkPointsService implements NetworkPointsService {

    private final AccountStore accounts;
    private final EconomicEngine engine;
    private final AmountParser parser;
    private final AmountFormatter formatter;
    private final AmountDisplayMode defaultDisplayMode;
    private final AtomicBoolean acceptingMutations = new AtomicBoolean(true);

    public DurableNetworkPointsService(
            AccountStore accounts,
            EconomicEngine engine,
            AmountParser parser,
            AmountFormatter formatter,
            AmountDisplayMode defaultDisplayMode) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.defaultDisplayMode = Objects.requireNonNull(defaultDisplayMode, "defaultDisplayMode");
    }

    @Override
    public Optional<BalanceSnapshot> cachedBalance(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return Optional.empty();
    }

    @Override
    public CompletableFuture<BalanceSnapshot> balance(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return this.accounts.find(playerId).thenCompose(account -> account
                .map(found -> CompletableFuture.completedFuture(found.snapshot()))
                .orElseGet(() -> CompletableFuture.failedFuture(
                        new NoSuchElementException("Points account does not exist"))));
    }

    @Override
    public CompletableFuture<BalanceSnapshot> refreshBalance(UUID playerId) {
        return balance(playerId);
    }

    @Override
    public CompletableFuture<MutationResult> award(AwardRequest request) {
        Objects.requireNonNull(request, "request");
        return CompletableFuture.completedFuture(new MutationResult(
                MutationStatus.SERVICE_UNAVAILABLE,
                MutationType.AWARD,
                request.context().operationId(),
                request.playerId(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), false));
    }

    @Override
    public CompletableFuture<MutationResult> credit(CreditRequest request) {
        return accepting() ? this.engine.credit(request) : unavailable(request.playerId(), request.context().operationId(), MutationType.CREDIT);
    }

    @Override
    public CompletableFuture<MutationResult> debit(DebitRequest request) {
        return accepting() ? this.engine.debit(request) : unavailable(request.playerId(), request.context().operationId(), MutationType.DEBIT);
    }

    @Override
    public CompletableFuture<TransferResult> transfer(TransferRequest request) {
        Objects.requireNonNull(request, "request");
        if (accepting()) {
            return this.engine.transfer(request);
        }
        return CompletableFuture.completedFuture(new TransferResult(
                MutationStatus.SERVICE_UNAVAILABLE, request.context().operationId(), request.senderId(), request.recipientId(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false));
    }

    @Override
    public CompletableFuture<MutationResult> setBalance(SetBalanceRequest request) {
        return accepting() ? this.engine.setBalance(request) : unavailable(request.playerId(), request.context().operationId(), MutationType.SET_BALANCE);
    }

    @Override
    public Component formatAmount(BigDecimal amount) {
        return this.formatter.formatComponent(amount, this.defaultDisplayMode);
    }

    @Override
    public String formatAmountPlain(BigDecimal amount, AmountDisplayMode mode) {
        return this.formatter.format(amount, mode);
    }

    @Override
    public AmountParseResult parseAmount(String input) {
        return this.parser.parse(input);
    }

    public void stopAcceptingMutations() {
        this.acceptingMutations.set(false);
    }

    private boolean accepting() {
        return this.acceptingMutations.get();
    }

    private static CompletableFuture<MutationResult> unavailable(UUID playerId, UUID operationId, MutationType type) {
        return CompletableFuture.completedFuture(new MutationResult(
                MutationStatus.SERVICE_UNAVAILABLE, type, operationId, playerId,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), false));
    }
}
