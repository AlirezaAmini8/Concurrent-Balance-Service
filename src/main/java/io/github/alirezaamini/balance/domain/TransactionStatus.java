package io.github.alirezaamini.balance.domain;

public enum TransactionStatus {
    /** Claimed by an in-flight database transaction; never visible once committed. */
    PENDING,
    /** Balances were changed. */
    APPLIED,
    /** A business rule rejected the operation; balances are untouched and the outcome is final. */
    REJECTED
}
