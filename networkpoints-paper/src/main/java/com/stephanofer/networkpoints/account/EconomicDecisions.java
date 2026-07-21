package com.stephanofer.networkpoints.account;

import com.stephanofer.networkpoints.api.result.MutationStatus;
import java.math.BigDecimal;
import java.util.Objects;

final class EconomicDecisions {

    private EconomicDecisions() {
    }

    static Decision credit(BigDecimal balance, BigDecimal amount, BigDecimal maximumBalance) {
        BigDecimal after = balance.add(amount);
        return after.compareTo(maximumBalance) > 0
                ? Decision.rejected(MutationStatus.BALANCE_LIMIT_EXCEEDED)
                : Decision.success(after, amount);
    }

    static Decision debit(BigDecimal balance, BigDecimal amount) {
        return balance.compareTo(amount) < 0
                ? Decision.rejected(MutationStatus.INSUFFICIENT_FUNDS)
                : Decision.success(balance.subtract(amount), amount.negate());
    }

    static Decision setBalance(BigDecimal balance, BigDecimal amount, BigDecimal maximumBalance) {
        return amount.compareTo(maximumBalance) > 0
                ? Decision.rejected(MutationStatus.BALANCE_LIMIT_EXCEEDED)
                : Decision.success(amount, amount.subtract(balance));
    }

    record Decision(MutationStatus status, BigDecimal balanceAfter, BigDecimal delta) {
        Decision {
            Objects.requireNonNull(status, "status");
            if (status == MutationStatus.SUCCESS) {
                Objects.requireNonNull(balanceAfter, "balanceAfter");
                Objects.requireNonNull(delta, "delta");
            } else if (balanceAfter != null || delta != null) {
                throw new IllegalArgumentException("rejected decision must not contain balance changes");
            }
        }

        static Decision success(BigDecimal balanceAfter, BigDecimal delta) {
            return new Decision(MutationStatus.SUCCESS, balanceAfter, delta);
        }

        static Decision rejected(MutationStatus status) {
            return new Decision(status, null, null);
        }

        boolean success() {
            return this.status == MutationStatus.SUCCESS;
        }
    }
}
