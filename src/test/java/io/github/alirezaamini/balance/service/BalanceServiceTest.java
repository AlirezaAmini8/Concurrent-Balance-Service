package io.github.alirezaamini.balance.service;

import io.github.alirezaamini.balance.domain.LedgerEntryType;
import io.github.alirezaamini.balance.domain.RejectionReason;
import io.github.alirezaamini.balance.domain.Transaction;
import io.github.alirezaamini.balance.domain.TransactionStatus;
import io.github.alirezaamini.balance.domain.exception.AccountAlreadyExistsException;
import io.github.alirezaamini.balance.domain.exception.AccountNotFoundException;
import io.github.alirezaamini.balance.domain.exception.BalanceOverflowException;
import io.github.alirezaamini.balance.domain.exception.InsufficientFundsException;
import io.github.alirezaamini.balance.domain.exception.InvalidAmountException;
import io.github.alirezaamini.balance.domain.exception.SameAccountTransferException;
import io.github.alirezaamini.balance.domain.exception.TransactionNotFoundException;
import io.github.alirezaamini.balance.domain.exception.ValidationException;
import io.github.alirezaamini.balance.support.LedgerInvariants;
import io.github.alirezaamini.balance.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class BalanceServiceTest extends PostgresIntegrationTest {

    @Autowired
    BalanceService balanceService;

    @Autowired
    AccountService accountService;

    @Autowired
    TransactionQueryService transactionQueryService;

    @AfterEach
    void invariantsHold() {
        LedgerInvariants.assertHold(jdbc);
    }

    @Nested
    class Credit {

        @Test
        void increasesBalance() {
            accountService.createAccount("A", 1_000);

            balanceService.credit("A", 500, "TX-1");

            assertThat(balanceService.getBalance("A")).isEqualTo(1_500);
            assertThat(transactionQueryService.getTransaction("TX-1").status()).isEqualTo(TransactionStatus.APPLIED);
        }

        @Test
        void thatWouldOverflowIsRefusedAndNotRecorded() {
            accountService.createAccount("A", Long.MAX_VALUE);

            assertThatThrownBy(() -> balanceService.credit("A", 1, "TX-1"))
                    .isInstanceOf(BalanceOverflowException.class);

            assertThat(balanceService.getBalance("A")).isEqualTo(Long.MAX_VALUE);
            assertNotRecorded("TX-1");
        }
    }

    @Nested
    class Debit {

        @Test
        void decreasesBalanceWhenFundsAreSufficient() {
            accountService.createAccount("A", 1_000);

            balanceService.debit("A", 700, "TX-1");

            assertThat(balanceService.getBalance("A")).isEqualTo(300);
        }

        @Test
        void ofTheWholeBalanceLeavesZero() {
            accountService.createAccount("A", 1_000);

            balanceService.debit("A", 1_000, "TX-1");

            assertThat(balanceService.getBalance("A")).isZero();
        }

        @Test
        void isRejectedWhenFundsAreInsufficientAndRejectionIsRecorded() {
            accountService.createAccount("A", 1_000);

            assertThatThrownBy(() -> balanceService.debit("A", 1_200, "TX-1"))
                    .isInstanceOf(InsufficientFundsException.class);

            assertThat(balanceService.getBalance("A")).isEqualTo(1_000);
            Transaction transaction = transactionQueryService.getTransaction("TX-1");
            assertThat(transaction.status()).isEqualTo(TransactionStatus.REJECTED);
            assertThat(transaction.rejectionReason()).isEqualTo(RejectionReason.INSUFFICIENT_FUNDS);
        }
    }

    @Nested
    class Transfer {

        @Test
        void movesMoneyBetweenAccounts() {
            accountService.createAccount("A", 1_000);
            accountService.createAccount("B", 500);

            balanceService.transfer("A", "B", 300, "TX-1");

            assertThat(balanceService.getBalance("A")).isEqualTo(700);
            assertThat(balanceService.getBalance("B")).isEqualTo(800);
            assertThat(accountService.getLedger("A"))
                    .extracting("type", "amount", "balanceAfter", "transactionId")
                    .containsExactly(
                            tuple(LedgerEntryType.OPENING, 1_000L, 1_000L, null),
                            tuple(LedgerEntryType.DEBIT, -300L, 700L, "TX-1"));
            assertThat(accountService.getLedger("B"))
                    .extracting("type", "amount", "balanceAfter", "transactionId")
                    .containsExactly(
                            tuple(LedgerEntryType.OPENING, 500L, 500L, null),
                            tuple(LedgerEntryType.CREDIT, 300L, 800L, "TX-1"));
        }

        @Test
        void isRejectedAsAWholeWhenSourceHasInsufficientFunds() {
            accountService.createAccount("A", 1_000);
            accountService.createAccount("B", 500);

            assertThatThrownBy(() -> balanceService.transfer("A", "B", 1_001, "TX-1"))
                    .isInstanceOf(InsufficientFundsException.class);

            assertThat(balanceService.getBalance("A")).isEqualTo(1_000);
            assertThat(balanceService.getBalance("B")).isEqualTo(500);
        }

        @Test
        void toUnknownAccountLeavesSourceUntouched() {
            accountService.createAccount("A", 1_000);

            assertThatThrownBy(() -> balanceService.transfer("A", "UNKNOWN", 300, "TX-1"))
                    .isInstanceOf(AccountNotFoundException.class);

            assertThat(balanceService.getBalance("A")).isEqualTo(1_000);
            assertNotRecorded("TX-1");
        }

        @Test
        void fromUnknownAccountLeavesDestinationUntouched() {
            accountService.createAccount("B", 500);

            assertThatThrownBy(() -> balanceService.transfer("UNKNOWN", "B", 300, "TX-1"))
                    .isInstanceOf(AccountNotFoundException.class);

            assertThat(balanceService.getBalance("B")).isEqualTo(500);
            assertNotRecorded("TX-1");
        }

        @Test
        void toTheSameAccountIsRejectedAsInvalid() {
            accountService.createAccount("A", 1_000);

            assertThatThrownBy(() -> balanceService.transfer("A", "A", 100, "TX-100"))
                    .isInstanceOf(SameAccountTransferException.class);

            assertThat(balanceService.getBalance("A")).isEqualTo(1_000);
            assertNotRecorded("TX-100");
        }
    }

    @Nested
    class Validation {

        @Test
        void nonPositiveAmountIsRejectedBeforeTouchingTheDatabase() {
            accountService.createAccount("A", 1_000);

            assertThatThrownBy(() -> balanceService.credit("A", 0, "TX-1")).isInstanceOf(InvalidAmountException.class);
            assertThatThrownBy(() -> balanceService.debit("A", -5, "TX-2")).isInstanceOf(InvalidAmountException.class);

            assertThat(balanceService.getBalance("A")).isEqualTo(1_000);
            assertNotRecorded("TX-1");
            assertNotRecorded("TX-2");
        }

        @Test
        void operationsOnUnknownAccountFailAndAreNotRecorded() {
            assertThatThrownBy(() -> balanceService.credit("UNKNOWN", 10, "TX-1"))
                    .isInstanceOf(AccountNotFoundException.class);
            assertThatThrownBy(() -> balanceService.debit("UNKNOWN", 10, "TX-2"))
                    .isInstanceOf(AccountNotFoundException.class);
            assertThatThrownBy(() -> balanceService.getBalance("UNKNOWN"))
                    .isInstanceOf(AccountNotFoundException.class);

            assertNotRecorded("TX-1");
            assertNotRecorded("TX-2");
        }

        @Test
        void accountCannotBeOpenedTwiceOrWithNegativeBalance() {
            accountService.createAccount("A", 1_000);

            assertThatThrownBy(() -> accountService.createAccount("A", 5))
                    .isInstanceOf(AccountAlreadyExistsException.class);
            assertThatThrownBy(() -> accountService.createAccount("B", -1))
                    .isInstanceOf(ValidationException.class);
            assertThat(balanceService.getBalance("A")).isEqualTo(1_000);
        }
    }

    private void assertNotRecorded(String transactionId) {
        assertThatThrownBy(() -> transactionQueryService.getTransaction(transactionId))
                .isInstanceOf(TransactionNotFoundException.class);
    }
}
