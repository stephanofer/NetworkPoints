package com.stephanofer.networkpoints.service;

import com.stephanofer.networkpoints.account.BalanceCache;
import com.stephanofer.networkpoints.account.EconomicEngine;
import com.stephanofer.networkpoints.amount.AmountFormatter;
import com.stephanofer.networkpoints.amount.AmountParser;
import com.stephanofer.networkpoints.config.ConfigSnapshot;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

public final class DurableNetworkPointsService implements NetworkPointsService {

    private final EconomicEngine engine;
    private final BalanceCache balances;
    private final PostCommitCoordinator postCommit;
    private volatile Presentation presentation;
    private final AtomicBoolean acceptingMutations = new AtomicBoolean(true);
    private final Set<CompletableFuture<?>> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Object mutationGate = new Object();

    public DurableNetworkPointsService(
            EconomicEngine engine,
            BalanceCache balances,
            PostCommitCoordinator postCommit,
            AmountParser parser,
            AmountFormatter formatter,
            AmountDisplayMode defaultDisplayMode,
            ConfigSnapshot.Currency currency) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.balances = Objects.requireNonNull(balances, "balances");
        this.postCommit = Objects.requireNonNull(postCommit, "postCommit");
        this.presentation = new Presentation(parser, formatter, defaultDisplayMode, currency);
    }

    @Override
    public Optional<BalanceSnapshot> cachedBalance(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return this.balances.getIfPresent(playerId);
    }

    @Override
    public CompletableFuture<BalanceSnapshot> balance(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return this.balances.get(playerId);
    }

    @Override
    public CompletableFuture<BalanceSnapshot> refreshBalance(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return this.balances.refresh(playerId);
    }

    @Override
    public CompletableFuture<MutationResult> award(AwardRequest request) {
        Objects.requireNonNull(request, "request");
        return startMutation(
                () -> this.engine.award(request).thenApply(this.postCommit::afterMutation),
                () -> unavailable(request.playerId(), request.context().operationId(), MutationType.AWARD));
    }

    @Override
    public CompletableFuture<MutationResult> credit(CreditRequest request) {
        return startMutation(
                () -> this.engine.credit(request).thenApply(this.postCommit::afterMutation),
                () -> unavailable(request.playerId(), request.context().operationId(), MutationType.CREDIT));
    }

    @Override
    public CompletableFuture<MutationResult> debit(DebitRequest request) {
        return startMutation(
                () -> this.engine.debit(request).thenApply(this.postCommit::afterMutation),
                () -> unavailable(request.playerId(), request.context().operationId(), MutationType.DEBIT));
    }

    @Override
    public CompletableFuture<TransferResult> transfer(TransferRequest request) {
        Objects.requireNonNull(request, "request");
        return startMutation(
                () -> this.engine.transfer(request).thenApply(this.postCommit::afterTransfer),
                () -> CompletableFuture.completedFuture(new TransferResult(
                        MutationStatus.SERVICE_UNAVAILABLE, request.context().operationId(), request.senderId(), request.recipientId(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false)));
    }

    @Override
    public CompletableFuture<MutationResult> setBalance(SetBalanceRequest request) {
        return startMutation(
                () -> this.engine.setBalance(request).thenApply(this.postCommit::afterMutation),
                () -> unavailable(request.playerId(), request.context().operationId(), MutationType.SET_BALANCE));
    }

    @Override
    public Component formatAmount(BigDecimal amount) {
        Presentation current = this.presentation;
        return MiniMessage.miniMessage().deserialize(current.currency().displayFormat(),
                Placeholder.component("amount", current.formatter().formatComponent(amount, current.defaultDisplayMode())),
                Placeholder.unparsed("symbol", current.currency().symbol()));
    }

    @Override
    public String formatAmountPlain(BigDecimal amount, AmountDisplayMode mode) {
        return this.presentation.formatter().format(amount, mode);
    }

    @Override
    public AmountParseResult parseAmount(String input) {
        return this.presentation.parser().parse(input);
    }

    public void updatePresentation(AmountParser parser, AmountFormatter formatter, AmountDisplayMode defaultDisplayMode,
                                   ConfigSnapshot.Currency currency) {
        this.presentation = new Presentation(parser, formatter, defaultDisplayMode, currency);
    }

    public void stopAcceptingMutations() {
        synchronized (this.mutationGate) {
            this.acceptingMutations.set(false);
        }
    }

    public CompletableFuture<Void> inFlightOperations() {
        return CompletableFuture.allOf(this.inFlight.toArray(CompletableFuture[]::new));
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> operation) {
        this.inFlight.add(operation);
        operation.whenComplete((result, failure) -> this.inFlight.remove(operation));
        return operation;
    }

    private <T> CompletableFuture<T> startMutation(
            Supplier<CompletableFuture<T>> operation,
            Supplier<CompletableFuture<T>> unavailable) {
        synchronized (this.mutationGate) {
            if (!this.acceptingMutations.get()) {
                return unavailable.get();
            }
            return track(operation.get());
        }
    }

    private static CompletableFuture<MutationResult> unavailable(UUID playerId, UUID operationId, MutationType type) {
        return CompletableFuture.completedFuture(new MutationResult(
                MutationStatus.SERVICE_UNAVAILABLE, type, operationId, playerId,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), false));
    }

    private record Presentation(AmountParser parser, AmountFormatter formatter,
                                AmountDisplayMode defaultDisplayMode, ConfigSnapshot.Currency currency) {
        private Presentation {
            Objects.requireNonNull(parser, "parser");
            Objects.requireNonNull(formatter, "formatter");
            Objects.requireNonNull(defaultDisplayMode, "defaultDisplayMode");
            Objects.requireNonNull(currency, "currency");
        }
    }
}
