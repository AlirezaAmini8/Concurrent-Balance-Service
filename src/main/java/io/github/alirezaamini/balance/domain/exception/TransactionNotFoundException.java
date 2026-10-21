package io.github.alirezaamini.balance.domain.exception;

public class TransactionNotFoundException extends BalanceException {

    public TransactionNotFoundException(String transactionId) {
        super("Transaction " + transactionId + " does not exist");
    }
}
