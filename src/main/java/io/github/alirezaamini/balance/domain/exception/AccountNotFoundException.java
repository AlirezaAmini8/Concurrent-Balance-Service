package io.github.alirezaamini.balance.domain.exception;

public class AccountNotFoundException extends BalanceException {

    private final String accountId;

    public AccountNotFoundException(String accountId) {
        super("Account " + accountId + " does not exist");
        this.accountId = accountId;
    }

    public String accountId() {
        return accountId;
    }
}
