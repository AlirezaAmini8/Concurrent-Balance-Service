package io.github.alirezaamini.balance.domain;

import io.github.alirezaamini.balance.domain.exception.InvalidAmountException;
import io.github.alirezaamini.balance.domain.exception.SameAccountTransferException;
import io.github.alirezaamini.balance.domain.exception.ValidationException;

import java.util.List;
import java.util.stream.Stream;

public record TransactionRequest(
        String transactionId,
        TransactionType type,
        String sourceAccountId,
        String destinationAccountId,
        long amount) {

    public static final int MAX_TRANSACTION_ID_LENGTH = 128;
    public static final int MAX_ACCOUNT_ID_LENGTH = 64;

    public TransactionRequest {
        requireId("transactionId", transactionId, MAX_TRANSACTION_ID_LENGTH);
        if (amount <= 0) {
            throw new InvalidAmountException(amount);
        }
        switch (type) {
            case CREDIT -> {
                requireAbsent("sourceAccountId", sourceAccountId);
                requireId("accountId", destinationAccountId, MAX_ACCOUNT_ID_LENGTH);
            }
            case DEBIT -> {
                requireId("accountId", sourceAccountId, MAX_ACCOUNT_ID_LENGTH);
                requireAbsent("destinationAccountId", destinationAccountId);
            }
            case TRANSFER -> {
                requireId("sourceAccountId", sourceAccountId, MAX_ACCOUNT_ID_LENGTH);
                requireId("destinationAccountId", destinationAccountId, MAX_ACCOUNT_ID_LENGTH);
                if (sourceAccountId.equals(destinationAccountId)) {
                    throw new SameAccountTransferException(sourceAccountId);
                }
            }
        }
    }

    public static TransactionRequest credit(String accountId, long amount, String transactionId) {
        return new TransactionRequest(transactionId, TransactionType.CREDIT, null, accountId, amount);
    }

    public static TransactionRequest debit(String accountId, long amount, String transactionId) {
        return new TransactionRequest(transactionId, TransactionType.DEBIT, accountId, null, amount);
    }

    public static TransactionRequest transfer(String sourceAccountId, String destinationAccountId,
            long amount, String transactionId) {
        return new TransactionRequest(transactionId, TransactionType.TRANSFER,
                sourceAccountId, destinationAccountId, amount);
    }

    /**
     * Involved accounts in a globally consistent (lexicographic) order. Locking in
     * this order is
     * what makes concurrent transfers A->B and B->A deadlock-free.
     */
    public List<String> accountIdsInLockOrder() {
        return Stream.of(sourceAccountId, destinationAccountId)
                .filter(id -> id != null)
                .sorted()
                .toList();
    }

    public boolean hasSource() {
        return sourceAccountId != null;
    }

    public boolean hasDestination() {
        return destinationAccountId != null;
    }

    private static void requireId(String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new ValidationException(field + " must be at most " + maxLength + " characters");
        }
    }

    private static void requireAbsent(String field, String value) {
        if (value != null) {
            throw new IllegalArgumentException(field + " must be null for this transaction type");
        }
    }
}
