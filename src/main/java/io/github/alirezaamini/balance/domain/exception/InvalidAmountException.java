package io.github.alirezaamini.balance.domain.exception;

public class InvalidAmountException extends ValidationException {

    public InvalidAmountException(long amount) {
        super("Amount must be positive but was " + amount);
    }
}
