package com.stephanofer.networkpoints.payment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PaymentSessionRegistry {
    private final Clock clock;
    private final Map<UUID, PaymentSession> sessions = new ConcurrentHashMap<>();

    public PaymentSessionRegistry(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PaymentSession open(UUID senderId, UUID recipientId, java.math.BigDecimal amount, Duration lifetime) {
        Objects.requireNonNull(lifetime, "lifetime");
        if (lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("lifetime must be positive");
        }
        Instant expiresAt;
        try {
            expiresAt = this.clock.instant().plus(lifetime);
        } catch (java.time.DateTimeException | ArithmeticException overflow) {
            expiresAt = Instant.MAX;
        }
        PaymentSession session = new PaymentSession(
                UUID.randomUUID(), UUID.randomUUID(), senderId, recipientId, amount, expiresAt);
        this.sessions.put(senderId, session);
        return session;
    }

    public boolean isActive(UUID senderId, UUID token) {
        PaymentSession current = this.sessions.get(Objects.requireNonNull(senderId, "senderId"));
        return current != null && current.token().equals(Objects.requireNonNull(token, "token"))
                && this.clock.instant().isBefore(current.expiresAt());
    }

    public Claim claim(UUID senderId, UUID token) {
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(token, "token");
        Instant now = this.clock.instant();
        PaymentSession[] claimed = new PaymentSession[1];
        Status[] status = {Status.MISSING};
        this.sessions.compute(senderId, (ignored, current) -> {
            if (current == null || !current.token().equals(token)) {
                return current;
            }
            if (!now.isBefore(current.expiresAt())) {
                status[0] = Status.EXPIRED;
                return null;
            }
            claimed[0] = current;
            status[0] = Status.CLAIMED;
            return null;
        });
        return new Claim(status[0], java.util.Optional.ofNullable(claimed[0]));
    }

    public boolean remove(UUID senderId, UUID token) {
        PaymentSession current = this.sessions.get(senderId);
        return current != null && current.token().equals(token) && this.sessions.remove(senderId, current);
    }

    public void remove(UUID senderId) {
        this.sessions.remove(senderId);
    }

    public Set<UUID> clear() {
        Set<UUID> players = Set.copyOf(this.sessions.keySet());
        this.sessions.clear();
        return players;
    }

    public enum Status {
        CLAIMED,
        MISSING,
        EXPIRED
    }

    public record Claim(Status status, java.util.Optional<PaymentSession> session) {
        public Claim {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(session, "session");
        }
    }
}
