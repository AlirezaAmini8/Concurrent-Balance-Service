package io.github.alirezaamini.balance.domain.exception;

/**
 * The operation was rejected because the source account cannot cover it. The rejection is recorded
 * against the transactionId, so replays keep failing the same way.
 */
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
