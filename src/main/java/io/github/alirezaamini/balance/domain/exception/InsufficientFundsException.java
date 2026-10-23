package io.github.alirezaamini.balance.domain.exception;

public class InsufficientFundsException extends BalanceException {

    private final String transactionId;

    public InsufficientFundsException(String transactionId, String accountId) {
        super("Transaction " + transactionId + " rejected: insufficient funds on account " + accountId);
        this.transactionId = transactionId;
    }

    public String transactionId() {
        return transactionId;
    }
}
