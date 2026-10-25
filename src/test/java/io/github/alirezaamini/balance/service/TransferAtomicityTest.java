package io.github.alirezaamini.balance.service;

import io.github.alirezaamini.balance.domain.TransactionStatus;
import io.github.alirezaamini.balance.domain.exception.TransactionNotFoundException;
import io.github.alirezaamini.balance.persistence.AccountRepository;
import io.github.alirezaamini.balance.support.ConcurrentRunner;
import io.github.alirezaamini.balance.support.DatabaseInvariants;
import io.github.alirezaamini.balance.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * Simulates an infrastructure failure in the middle of a transfer - after the source has already been
 * debited, before the destination is credited - and checks that nothing of it survives.
 */
class TransferAtomicityTest extends PostgresIntegrationTest {

    @Autowired
    BalanceService balanceService;

    @Autowired
    AccountService accountService;

    @Autowired
    TransactionQueryService transactionQueryService;

    @MockitoSpyBean
    AccountRepository accountRepository;

    @Test
    void failureAfterDebitRollsBackTheWholeTransfer() {
        accountService.createAccount("A", 1_000);
        accountService.createAccount("B", 500);
        doThrow(new DataAccessResourceFailureException("simulated connection loss"))
                .when(accountRepository)
                .updateBalance(argThat(account -> account.id().equals("B")));

        assertThatThrownBy(() -> balanceService.transfer("A", "B", 300, "TX-1"))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(balanceService.getBalance("A")).isEqualTo(1_000);
        assertThat(balanceService.getBalance("B")).isEqualTo(500);
        assertThatThrownBy(() -> transactionQueryService.getTransaction("TX-1"))
                .isInstanceOf(TransactionNotFoundException.class);
        DatabaseInvariants.assertHold(jdbc);
    }

    @Test
    void clientCanSafelyRetryTheSameTransactionIdAfterAFailure() {
        accountService.createAccount("A", 1_000);
        accountService.createAccount("B", 500);
        doThrow(new DataAccessResourceFailureException("simulated connection loss"))
                .when(accountRepository)
                .updateBalance(argThat(account -> account.id().equals("B")));
        assertThatThrownBy(() -> balanceService.transfer("A", "B", 300, "TX-1"))
                .isInstanceOf(DataAccessResourceFailureException.class);

        reset(accountRepository);
        balanceService.transfer("A", "B", 300, "TX-1");

        assertThat(balanceService.getBalance("A")).isEqualTo(700);
        assertThat(balanceService.getBalance("B")).isEqualTo(800);
        DatabaseInvariants.assertHold(jdbc);
    }

    @Test
    void whenTheFirstOfConcurrentDuplicatesFailsAWaitingDuplicateAppliesItInstead() {
        accountService.createAccount("A", 1_000);
        accountService.createAccount("B", 500);
        // Only the first attempt to reach the credit fails; its rollback releases the claim on TX-1.
        doThrow(new DataAccessResourceFailureException("simulated connection loss"))
                .doCallRealMethod()
                .when(accountRepository)
                .updateBalance(argThat(account -> account.id().equals("B")));

        ConcurrentRunner.Result result = ConcurrentRunner.run(16, IntStream.range(0, 50)
                .<Runnable>mapToObj(i -> () -> balanceService.transfer("A", "B", 300, "TX-1"))
                .toList());

        assertThat(result.failuresOf(DataAccessResourceFailureException.class)).isEqualTo(1);
        assertThat(result.succeeded()).isEqualTo(49);
        assertThat(balanceService.getBalance("A")).isEqualTo(700);
        assertThat(balanceService.getBalance("B")).isEqualTo(800);
        assertThat(transactionQueryService.getTransaction("TX-1").status()).isEqualTo(TransactionStatus.APPLIED);
        DatabaseInvariants.assertHold(jdbc);
    }
}
