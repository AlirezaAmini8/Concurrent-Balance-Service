package io.github.alirezaamini.balance.domain;

import io.github.alirezaamini.balance.domain.exception.BalanceOverflowException;

public record Account(String id, long balance) {

    public boolean canDebit(long amount) {
        return balance >= amount;
    }

    public Account debit(long amount) {
        if (!canDebit(amount)) {
            throw new IllegalStateException("Debit of " + amount + " would make account " + id + " negative");
        }
        return new Account(id, balance - amount);
    }

    public Account credit(long amount) {
        try {
            return new Account(id, Math.addExact(balance, amount));
        } catch (ArithmeticException e) {
            throw new BalanceOverflowException(id);
        }
    }
}
