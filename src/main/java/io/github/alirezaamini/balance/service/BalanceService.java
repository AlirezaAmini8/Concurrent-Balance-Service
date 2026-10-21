package io.github.alirezaamini.balance.service;

/**
 * Balance operations. All amounts are positive values in minor units.
 * <p>
 * Every mutating operation is idempotent on {@code transactionId}: replaying it with the same
 * parameters returns the original outcome (success, or the same rejection) without touching balances again.
 *
 * @throws io.github.alirezaamini.balance.domain.exception.ValidationException          invalid input; nothing recorded
 * @throws io.github.alirezaamini.balance.domain.exception.AccountNotFoundException      unknown account; nothing recorded
 * @throws io.github.alirezaamini.balance.domain.exception.InsufficientFundsException    rejected; recorded as final
 * @throws io.github.alirezaamini.balance.domain.exception.IdempotencyConflictException transactionId reused for a different operation
 */
public interface BalanceService {

    void credit(String accountId, long amount, String transactionId);

    void debit(String accountId, long amount, String transactionId);

    void transfer(String sourceAccountId, String destinationAccountId, long amount, String transactionId);

    long getBalance(String accountId);
}
