package io.github.alirezaamini.balance.domain.exception;

/**
 * A transactionId was reused for an operation with different parameters.
 */
public class IdempotencyConflictException extends BalanceException {

    public IdempotencyConflictException(String transactionId) {
        super("Transaction " + transactionId + " was already used for a different operation");
    }
}
