package io.github.alirezaamini.balance.service;

import io.github.alirezaamini.balance.domain.Transaction;
import io.github.alirezaamini.balance.domain.exception.TransactionNotFoundException;
import io.github.alirezaamini.balance.persistence.TransactionRepository;
import org.springframework.stereotype.Service;

@Service
public class TransactionQueryService {

    private final TransactionRepository transactions;

    public TransactionQueryService(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    public Transaction getTransaction(String transactionId) {
        return transactions.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    }
}
