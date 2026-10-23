package io.github.alirezaamini.balance.domain;

import java.time.Instant;

public record LedgerEntry(
                long id,
                String accountId,
                String transactionId,
                LedgerEntryType type,
                long amount,
                long balanceAfter,
                Instant createdAt) {
}
