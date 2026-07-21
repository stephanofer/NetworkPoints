package com.stephanofer.networkpoints.api.amount;

import java.math.BigDecimal;
import java.util.Objects;

/** Typed outcome of parsing an amount without throwing for invalid user input. */
public sealed interface AmountParseResult permits AmountParseResult.Success, AmountParseResult.Failure {
    /**
     * A successfully parsed amount.
     *
     * @param amount the non-negative amount, normalized to scale two
     */
    record Success(BigDecimal amount) implements AmountParseResult {
        /**
         * Validates and creates a successful result.
         *
         * @param amount the non-negative amount with at most two decimal places
         */
        public Success {
            amount = MonetaryAmounts.nonNegative(amount, "amount");
        }
    }

    /**
     * A rejected amount input.
     *
     * @param reason the stable rejection category
     */
    record Failure(Reason reason) implements AmountParseResult {
        /**
         * Creates a failed result.
         *
         * @param reason the stable rejection category
         */
        public Failure {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Stable categories consumers can map to localized feedback. */
    enum Reason {
        /** The input was null or empty. */
        EMPTY,
        /** The input did not match the supported numeric syntax. */
        INVALID_FORMAT,
        /** The numeric portion contained more than two decimal places. */
        TOO_MANY_DECIMALS,
        /** The input used an unconfigured magnitude suffix. */
        UNKNOWN_SUFFIX,
        /** The expanded amount fell outside the configured limits or numeric range. */
        OUT_OF_RANGE
    }
}
