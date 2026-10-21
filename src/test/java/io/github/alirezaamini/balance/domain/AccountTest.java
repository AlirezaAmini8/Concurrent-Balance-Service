package io.github.alirezaamini.balance.domain;

import io.github.alirezaamini.balance.domain.exception.BalanceOverflowException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    @Test
    void debitAndCreditProduceNewSnapshots() {
        Account account = new Account("A", 1_000);

        assertThat(account.debit(700).balance()).isEqualTo(300);
        assertThat(account.credit(500).balance()).isEqualTo(1_500);
        assertThat(account.balance()).isEqualTo(1_000);
    }

    @Test
    void debitOfExactBalanceIsAllowed() {
        assertThat(new Account("A", 1_000).debit(1_000).balance()).isZero();
    }

    @Test
    void debitBeyondBalanceIsRefused() {
        Account account = new Account("A", 1_000);

        assertThat(account.canDebit(1_200)).isFalse();
        assertThatThrownBy(() -> account.debit(1_200)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void creditThatWouldOverflowIsRefused() {
        assertThatThrownBy(() -> new Account("A", Long.MAX_VALUE).credit(1))
                .isInstanceOf(BalanceOverflowException.class);
    }
}
