package io.github.alirezaamini.balance.service;

import io.github.alirezaamini.balance.domain.exception.AccountNotFoundException;
import io.github.alirezaamini.balance.domain.exception.IdempotencyConflictException;
import io.github.alirezaamini.balance.domain.exception.InsufficientFundsException;
import io.github.alirezaamini.balance.support.LedgerInvariants;
import io.github.alirezaamini.balance.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sequential retries. Concurrent duplicates are covered in {@code ConcurrencyTest}.
 */
class IdempotencyTest extends PostgresIntegrationTest {

    @Autowired
    BalanceService balanceService;

    @Autowired
    AccountService accountService;

    @AfterEach
    void invariantsHold() {
        LedgerInvariants.assertHold(jdbc);
    }

    @Test
    void repeatedCreditIsAppliedOnce() {
        accountService.createAccount("A", 1_000);

        balanceService.credit("A", 100, "TX-1");
        balanceService.credit("A", 100, "TX-1");
        balanceService.credit("A", 100, "TX-1");

        assertThat(balanceService.getBalance("A")).isEqualTo(1_100);
        assertThat(accountService.getLedger("A")).hasSize(2);
    }

    @Test
    void repeatedDebitIsAppliedOnce() {
        accountService.createAccount("A", 1_000);

        balanceService.debit("A", 100, "TX-1");
        balanceService.debit("A", 100, "TX-1");
        balanceService.debit("A", 100, "TX-1");

        assertThat(balanceService.getBalance("A")).isEqualTo(900);
    }

    @Test
    void repeatedTransferIsAppliedOnce() {
        accountService.createAccount("A", 1_000);
        accountService.createAccount("B", 500);

        balanceService.transfer("A", "B", 300, "TX-1");
        balanceService.transfer("A", "B", 300, "TX-1");
        balanceService.transfer("A", "B", 300, "TX-1");

        assertThat(balanceService.getBalance("A")).isEqualTo(700);
        assertThat(balanceService.getBalance("B")).isEqualTo(800);
    }

    @Test
    void rejectionIsFinalEvenIfFundsArriveLater() {
        accountService.createAccount("A", 1_000);
        assertThatThrownBy(() -> balanceService.debit("A", 1_200, "TX-1"))
                .isInstanceOf(InsufficientFundsException.class);

        balanceService.credit("A", 5_000, "TX-2");

        assertThatThrownBy(() -> balanceService.debit("A", 1_200, "TX-1"))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(balanceService.getBalance("A")).isEqualTo(6_000);
    }

    @Test
    void reusingTransactionIdWithDifferentAmountIsAConflict() {
        accountService.createAccount("A", 1_000);
        balanceService.credit("A", 500, "TX-1");

        assertThatThrownBy(() -> balanceService.credit("A", 600, "TX-1"))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(balanceService.getBalance("A")).isEqualTo(1_500);
    }

    @Test
    void reusingTransactionIdForAnotherOperationTypeIsAConflict() {
        accountService.createAccount("A", 1_000);
        accountService.createAccount("B", 500);
        balanceService.credit("A", 500, "TX-1");

        assertThatThrownBy(() -> balanceService.debit("A", 500, "TX-1"))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> balanceService.transfer("A", "B", 500, "TX-1"))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(balanceService.getBalance("A")).isEqualTo(1_500);
        assertThat(balanceService.getBalance("B")).isEqualTo(500);
    }

    @Test
    void reusingTransactionIdWithSwappedTransferDirectionIsAConflict() {
        accountService.createAccount("A", 1_000);
        accountService.createAccount("B", 500);
        balanceService.transfer("A", "B", 300, "TX-1");

        assertThatThrownBy(() -> balanceService.transfer("B", "A", 300, "TX-1"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void failureThatWasNotRecordedCanBeRetriedWithTheSameTransactionId() {
        assertThatThrownBy(() -> balanceService.credit("A", 100, "TX-1"))
                .isInstanceOf(AccountNotFoundException.class);

        accountService.createAccount("A", 0);
        balanceService.credit("A", 100, "TX-1");

        assertThat(balanceService.getBalance("A")).isEqualTo(100);
    }
}
