package io.github.alirezaamini.balance.domain.exception;

public class SameAccountTransferException extends ValidationException {

    public SameAccountTransferException(String accountId) {
        super("Source and destination account must differ, both were " + accountId);
    }
}
