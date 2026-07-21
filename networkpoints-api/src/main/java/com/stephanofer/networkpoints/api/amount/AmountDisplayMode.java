package com.stephanofer.networkpoints.api.amount;

/** Public amount presentation modes. */
public enum AmountDisplayMode {
    /** Plain decimal notation without grouping or insignificant trailing zeros. */
    RAW,
    /** Locale-configured decimal notation with digit grouping. */
    GROUPED,
    /** Abbreviated notation using the configured magnitude suffixes. */
    COMPACT
}
