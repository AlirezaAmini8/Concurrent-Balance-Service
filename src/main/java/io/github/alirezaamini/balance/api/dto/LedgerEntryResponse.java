package io.github.alirezaamini.balance.api.dto;

import io.github.alirezaamini.balance.domain.LedgerEntry;
import io.github.alirezaamini.balance.domain.LedgerEntryType;

import java.time.Instant;

public record LedgerEntryResponse(
        long entryId,
        String transactionId,
        LedgerEntryType type,
        long amount,
        long balanceAfter,
        Instant createdAt
) {

    public static LedgerEntryResponse from(LedgerEntry entry) {
        return new LedgerEntryResponse(entry.id(), entry.transactionId(), entry.type(), entry.amount(),
                entry.balanceAfter(), entry.createdAt());
    }
}
