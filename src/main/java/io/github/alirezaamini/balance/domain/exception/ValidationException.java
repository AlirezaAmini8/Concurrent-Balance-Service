package io.github.alirezaamini.balance.domain.exception;

/**
 * The request itself is malformed. Nothing is recorded, so the same transactionId may be retried with valid input.
 */
public class ValidationException extends BalanceException {

    public ValidationException(String message) {
        super(message);
    }
}
