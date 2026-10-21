package io.github.alirezaamini.balance.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * The persisted outcome of a {@link TransactionRequest}, keyed by its transactionId.
 */
public record Transaction(
        String id,
        TransactionType type,
        String sourceAccountId,
        String destinationAccountId,
        long amount,
        TransactionStatus status,
        RejectionReason rejectionReason,
        Instant createdAt
) {

    /**
     * Whether {@code request} is a replay of this transaction, i.e. carries exactly the same parameters.
     * Reusing a transactionId for a different operation is a client error, not a replay.
     */
    public boolean isSameOperationAs(TransactionRequest request) {
        return id.equals(request.transactionId())
                && type == request.type()
                && Objects.equals(sourceAccountId, request.sourceAccountId())
                && Objects.equals(destinationAccountId, request.destinationAccountId())
                && amount == request.amount();
    }
}
