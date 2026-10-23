package io.github.alirezaamini.balance.domain;

import java.time.Instant;
import java.util.Objects;

public record Transaction(
        String id,
        TransactionType type,
        String sourceAccountId,
        String destinationAccountId,
        long amount,
        TransactionStatus status,
        RejectionReason rejectionReason,
        Instant createdAt) {

    public boolean isSameOperationAs(TransactionRequest request) {
        return id.equals(request.transactionId())
                && type == request.type()
                && Objects.equals(sourceAccountId, request.sourceAccountId())
                && Objects.equals(destinationAccountId, request.destinationAccountId())
                && amount == request.amount();
    }
}
