package io.github.alirezaamini.balance.domain;

import io.github.alirezaamini.balance.domain.exception.InvalidAmountException;
import io.github.alirezaamini.balance.domain.exception.SameAccountTransferException;
import io.github.alirezaamini.balance.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionRequestTest {

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsNonPositiveAmounts(long amount) {
        assertThatThrownBy(() -> TransactionRequest.credit("A", amount, "TX-1"))
                .isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> TransactionRequest.debit("A", amount, "TX-1"))
                .isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> TransactionRequest.transfer("A", "B", amount, "TX-1"))
                .isInstanceOf(InvalidAmountException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void rejectsBlankIdentifiers(String blank) {
        assertThatThrownBy(() -> TransactionRequest.credit("A", 10, blank))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> TransactionRequest.debit(blank, 10, "TX-1"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> TransactionRequest.transfer("A", blank, 10, "TX-1"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsOverlongTransactionId() {
        String tooLong = "x".repeat(TransactionRequest.MAX_TRANSACTION_ID_LENGTH + 1);

        assertThatThrownBy(() -> TransactionRequest.credit("A", 10, tooLong))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsTransferToSameAccount() {
        assertThatThrownBy(() -> TransactionRequest.transfer("A", "A", 100, "TX-100"))
                .isInstanceOf(SameAccountTransferException.class);
    }

    @Test
    void locksAccountsInLexicographicOrderRegardlessOfDirection() {
        assertThat(TransactionRequest.transfer("B", "A", 1, "TX-1").accountIdsInLockOrder())
                .containsExactly("A", "B");
        assertThat(TransactionRequest.transfer("A", "B", 1, "TX-2").accountIdsInLockOrder())
                .containsExactly("A", "B");
        assertThat(TransactionRequest.debit("A", 1, "TX-3").accountIdsInLockOrder())
                .containsExactly("A");
    }

    @Test
    void replayWithIdenticalParametersIsTheSameOperation() {
        TransactionRequest request = TransactionRequest.transfer("A", "B", 300, "TX-1");

        assertThat(appliedFrom(request).isSameOperationAs(request)).isTrue();
    }

    @Test
    void reuseOfTransactionIdWithDifferentParametersIsNotTheSameOperation() {
        Transaction stored = appliedFrom(TransactionRequest.transfer("A", "B", 300, "TX-1"));

        assertThat(stored.isSameOperationAs(TransactionRequest.transfer("A", "B", 301, "TX-1"))).isFalse();
        assertThat(stored.isSameOperationAs(TransactionRequest.transfer("B", "A", 300, "TX-1"))).isFalse();
        assertThat(stored.isSameOperationAs(TransactionRequest.debit("A", 300, "TX-1"))).isFalse();
    }

    private static Transaction appliedFrom(TransactionRequest request) {
        return new Transaction(request.transactionId(), request.type(), request.sourceAccountId(),
                request.destinationAccountId(), request.amount(), TransactionStatus.APPLIED, null, Instant.now());
    }
}
