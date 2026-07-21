package com.stephanofer.networkpoints.api.result;

/** Stable business outcomes for points mutations. */
public enum MutationStatus {
    /** The mutation committed successfully. */
    SUCCESS,
    /** The debit or transfer would overdraw the source account. */
    INSUFFICIENT_FUNDS,
    /** The resulting balance would exceed the configured maximum. */
    BALANCE_LIMIT_EXCEEDED,
    /** The requested amount violates operation or configured limits. */
    INVALID_AMOUNT,
    /** A required account does not exist. */
    ACCOUNT_NOT_FOUND,
    /** Booster data required to calculate an award is not ready. */
    BOOSTER_STATE_NOT_READY,
    /** The operation ID was previously used with different request data. */
    IDEMPOTENCY_CONFLICT,
    /** The operation could not be processed because the service is unavailable. */
    SERVICE_UNAVAILABLE
}
