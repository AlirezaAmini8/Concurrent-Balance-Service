package io.github.alirezaamini.balance.service;

import io.github.alirezaamini.balance.domain.Account;
import io.github.alirezaamini.balance.domain.LedgerEntryType;
import io.github.alirezaamini.balance.domain.RejectionReason;
import io.github.alirezaamini.balance.domain.Transaction;
import io.github.alirezaamini.balance.domain.TransactionRequest;
import io.github.alirezaamini.balance.domain.TransactionStatus;
import io.github.alirezaamini.balance.domain.exception.AccountNotFoundException;
import io.github.alirezaamini.balance.domain.exception.IdempotencyConflictException;
import io.github.alirezaamini.balance.domain.exception.InsufficientFundsException;
import io.github.alirezaamini.balance.persistence.AccountRepository;
import io.github.alirezaamini.balance.persistence.LedgerRepository;
import io.github.alirezaamini.balance.persistence.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Database-backed {@link BalanceService}.
 * <p>
 * Each operation runs in a single database transaction that:
 * <ol>
 *     <li>claims the transactionId (idempotency; concurrent duplicates block here and then replay),</li>
 *     <li>locks the involved account rows in a global order (no lost updates, no deadlocks),</li>
 *     <li>checks funds, writes the new balances and ledger entries,</li>
 *     <li>records the outcome against the transactionId.</li>
 * </ol>
 * Either everything commits or nothing does, which is what makes a transfer atomic.
 */
@Service
public class LedgerBalanceService implements BalanceService {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final LedgerRepository ledger;
    private final TransactionTemplate transactionTemplate;

    public LedgerBalanceService(AccountRepository accounts,
                                TransactionRepository transactions,
                                LedgerRepository ledger,
                                TransactionTemplate transactionTemplate) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.ledger = ledger;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void credit(String accountId, long amount, String transactionId) {
        execute(TransactionRequest.credit(accountId, amount, transactionId));
    }

    @Override
    public void debit(String accountId, long amount, String transactionId) {
        execute(TransactionRequest.debit(accountId, amount, transactionId));
    }

    @Override
    public void transfer(String sourceAccountId, String destinationAccountId, long amount, String transactionId) {
        execute(TransactionRequest.transfer(sourceAccountId, destinationAccountId, amount, transactionId));
    }

    @Override
    public long getBalance(String accountId) {
        return accounts.findById(accountId)
                .map(Account::balance)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    /**
     * A rejection has to be committed (so replays see it) but reported to the caller as an exception.
     * Throwing inside the transaction would roll the REJECTED record back, hence the exception is only
     * raised here, after the transaction template has committed.
     */
    private void execute(TransactionRequest request) {
        Transaction outcome = transactionTemplate.execute(status -> process(request));
        if (outcome.status() == TransactionStatus.REJECTED) {
            throw new InsufficientFundsException(outcome.id(), outcome.sourceAccountId());
        }
    }

    private Transaction process(TransactionRequest request) {
        if (!transactions.claim(request)) {
            return replay(request);
        }

        Map<String, Account> locked = lockAccounts(request);

        if (request.hasSource()) {
            Account source = locked.get(request.sourceAccountId());
            if (!source.canDebit(request.amount())) {
                return transactions.complete(request.transactionId(),
                        TransactionStatus.REJECTED, RejectionReason.INSUFFICIENT_FUNDS);
            }
            Account debited = source.debit(request.amount());
            accounts.updateBalance(debited);
            ledger.append(debited.id(), request.transactionId(), LedgerEntryType.DEBIT,
                    -request.amount(), debited.balance());
        }
        if (request.hasDestination()) {
            Account credited = locked.get(request.destinationAccountId()).credit(request.amount());
            accounts.updateBalance(credited);
            ledger.append(credited.id(), request.transactionId(), LedgerEntryType.CREDIT,
                    request.amount(), credited.balance());
        }
        return transactions.complete(request.transactionId(), TransactionStatus.APPLIED, null);
    }

    /**
     * The transactionId is already committed by an earlier (or concurrent, now finished) request.
     */
    private Transaction replay(TransactionRequest request) {
        Transaction existing = transactions.findById(request.transactionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Transaction " + request.transactionId() + " was claimed but cannot be read"));
        if (!existing.isSameOperationAs(request)) {
            throw new IdempotencyConflictException(request.transactionId());
        }
        return existing;
    }

    /**
     * Locks rows one by one in {@link TransactionRequest#accountIdsInLockOrder()} order. Every operation
     * acquires locks in the same order, so a cycle (and therefore a deadlock) cannot form.
     */
    private Map<String, Account> lockAccounts(TransactionRequest request) {
        Map<String, Account> locked = new HashMap<>();
        for (String accountId : request.accountIdsInLockOrder()) {
            Account account = accounts.findByIdForUpdate(accountId)
                    .orElseThrow(() -> new AccountNotFoundException(accountId));
            locked.put(accountId, account);
        }
        return locked;
    }
}
