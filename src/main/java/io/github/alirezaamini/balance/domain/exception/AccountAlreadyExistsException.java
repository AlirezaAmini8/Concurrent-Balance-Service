package io.github.alirezaamini.balance.domain.exception;

public class AccountAlreadyExistsException extends BalanceException {

    public AccountAlreadyExistsException(String accountId) {
        super("Account " + accountId + " already exists");
    }
}
