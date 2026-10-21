package io.github.alirezaamini.balance.domain.exception;

public class BalanceOverflowException extends BalanceException {

    public BalanceOverflowException(String accountId) {
        super("Credit would overflow the balance of account " + accountId);
    }
}
