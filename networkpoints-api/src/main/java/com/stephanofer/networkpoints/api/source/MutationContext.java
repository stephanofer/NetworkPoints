package com.stephanofer.networkpoints.api.source;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.key.Key;

/**
 * Immutable idempotency identity and audit metadata for one mutation.
 *
 * @param operationId the globally unique idempotency key
 * @param source the namespaced system or action that initiated the mutation
 * @param actorId the initiating actor, or empty for a system-initiated mutation
 * @param sourceReference an optional non-blank source-specific audit reference
 */
public record MutationContext(
        UUID operationId,
        Key source,
        Optional<UUID> actorId,
        Optional<String> sourceReference) {
    /**
     * Validates and creates mutation context.
     *
     * @param operationId the globally unique idempotency key
     * @param source the namespaced system or action that initiated the mutation
     * @param actorId the initiating actor, or empty for a system-initiated mutation
     * @param sourceReference an optional non-blank source-specific audit reference
     */
    public MutationContext {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(source, "source");
        actorId = Objects.requireNonNull(actorId, "actorId");
        sourceReference = Objects.requireNonNull(sourceReference, "sourceReference");
        sourceReference.ifPresent(reference -> {
            if (reference.isBlank()) {
                throw new IllegalArgumentException("sourceReference must not be blank");
            }
            if (reference.length() > 255) {
                throw new IllegalArgumentException("sourceReference must not exceed 255 characters");
            }
        });
        if (source.asString().length() > 128) {
            throw new IllegalArgumentException("source must not exceed 128 characters");
        }
    }

    /**
     * Creates context with an actor and source reference.
     *
     * @param operationId the globally unique idempotency key
     * @param source the namespaced system or action that initiated the mutation
     * @param actorId the initiating actor
     * @param sourceReference the non-blank source-specific audit reference
     * @return validated mutation context containing both optional values
     */
    public static MutationContext create(
            UUID operationId, Key source, UUID actorId, String sourceReference) {
        return new MutationContext(
                operationId, source, Optional.of(actorId), Optional.of(sourceReference));
    }
}
