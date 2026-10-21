package io.github.alirezaamini.balance.service;

import io.github.alirezaamini.balance.domain.Account;
import io.github.alirezaamini.balance.domain.LedgerEntry;
import io.github.alirezaamini.balance.domain.LedgerEntryType;
import io.github.alirezaamini.balance.domain.TransactionRequest;
import io.github.alirezaamini.balance.domain.exception.AccountAlreadyExistsException;
import io.github.alirezaamini.balance.domain.exception.AccountNotFoundException;
import io.github.alirezaamini.balance.domain.exception.ValidationException;
import io.github.alirezaamini.balance.persistence.AccountRepository;
import io.github.alirezaamini.balance.persistence.LedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final LedgerRepository ledger;

    public AccountService(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    /**
     * Opens an account. A positive opening balance is written to the ledger so that
     * the balance always equals the sum of the account's ledger entries.
     */
    @Transactional
    public Account createAccount(String accountId, long initialBalance) {
        if (accountId == null || accountId.isBlank()) {
            throw new ValidationException("accountId must not be blank");
        }
        if (accountId.length() > TransactionRequest.MAX_ACCOUNT_ID_LENGTH) {
            throw new ValidationException(
                    "accountId must be at most " + TransactionRequest.MAX_ACCOUNT_ID_LENGTH + " characters");
        }
        if (initialBalance < 0) {
            throw new ValidationException("initialBalance must not be negative");
        }

        Account account = new Account(accountId, initialBalance);
        if (!accounts.insert(account)) {
            throw new AccountAlreadyExistsException(accountId);
        }
        if (initialBalance > 0) {
            ledger.append(accountId, null, LedgerEntryType.OPENING, initialBalance, initialBalance);
        }
        return account;
    }

    public Account getAccount(String accountId) {
        return accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    public List<LedgerEntry> getLedger(String accountId) {
        getAccount(accountId);
        return ledger.findByAccountId(accountId);
    }
}
