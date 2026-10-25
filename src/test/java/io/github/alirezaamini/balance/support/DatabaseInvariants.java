package io.github.alirezaamini.balance.support;

import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the system-wide invariants directly in the database, independent of the service code.
 */
public final class DatabaseInvariants {

    private DatabaseInvariants() {
    }

    public static void assertHold(JdbcClient jdbc) {
        assertThat(jdbc.sql("SELECT count(*) FROM accounts WHERE balance < 0").query(Long.class).single())
                .as("accounts with a negative balance")
                .isZero();

        assertThat(jdbc.sql("SELECT count(*) FROM transactions WHERE status = 'PENDING'").query(Long.class).single())
                .as("transactions left in PENDING")
                .isZero();
    }
}
