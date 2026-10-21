package io.github.alirezaamini.balance.domain;

import java.time.Instant;

/**
 * One movement of money on one account. {@code amount} is signed: positive in, negative out.
 */
public record LedgerEntry(
        long id,
        String accountId,
        String transactionId,
        LedgerEntryType type,
        long amount,
        long balanceAfter,
        Instant createdAt
) {
}
