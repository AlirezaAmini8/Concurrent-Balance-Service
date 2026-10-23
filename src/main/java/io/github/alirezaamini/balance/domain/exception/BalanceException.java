package io.github.alirezaamini.balance.domain.exception;

public abstract class BalanceException extends RuntimeException {

    protected BalanceException(String message) {
        super(message);
    }
}
