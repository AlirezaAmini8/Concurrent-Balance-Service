package io.github.alirezaamini.balance.domain.exception;

/**
 * Base type for all expected, client-visible failures of the balance service.
 */
public abstract class BalanceException extends RuntimeException {

    protected BalanceException(String message) {
        super(message);
    }
}
