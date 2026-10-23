package io.github.alirezaamini.balance.domain.exception;

public class IdempotencyConflictException extends BalanceException {

    public IdempotencyConflictException(String transactionId) {
        super("Transaction " + transactionId + " was already used for a different operation");
    }
}
