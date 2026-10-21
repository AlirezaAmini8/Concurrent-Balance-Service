package io.github.alirezaamini.balance.api.dto;

import io.github.alirezaamini.balance.domain.RejectionReason;
import io.github.alirezaamini.balance.domain.Transaction;
import io.github.alirezaamini.balance.domain.TransactionStatus;
import io.github.alirezaamini.balance.domain.TransactionType;

import java.time.Instant;

public record TransactionResponse(
        String transactionId,
        TransactionType type,
        String sourceAccountId,
        String destinationAccountId,
        long amount,
        TransactionStatus status,
        RejectionReason rejectionReason,
        Instant createdAt
) {

    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(transaction.id(), transaction.type(), transaction.sourceAccountId(),
                transaction.destinationAccountId(), transaction.amount(), transaction.status(),
                transaction.rejectionReason(), transaction.createdAt());
    }
}
