package com.stephanofer.networkpoints.api.result;

/** The balance mutation represented by a result or post-commit event. */
public enum MutationType {
    /** A booster-eligible credit. */
    AWARD,
    /** A direct credit that does not apply boosters. */
    CREDIT,
    /** A direct debit. */
    DEBIT,
    /** An absolute balance assignment. */
    SET_BALANCE
}
