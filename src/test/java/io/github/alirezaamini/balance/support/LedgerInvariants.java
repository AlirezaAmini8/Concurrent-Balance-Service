package io.github.alirezaamini.balance.support;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the system-wide invariants directly in the database, independent of the service code.
 */
public final class LedgerInvariants {

    private LedgerInvariants() {
    }

    public static void assertHold(JdbcClient jdbc) {
        assertThat(jdbc.sql("SELECT count(*) FROM accounts WHERE balance < 0").query(Long.class).single())
                .as("accounts with a negative balance")
                .isZero();

        List<String> unreconciled = jdbc.sql("""
                        SELECT a.id
                        FROM accounts a
                        LEFT JOIN ledger_entries l ON l.account_id = a.id
                        GROUP BY a.id, a.balance
                        HAVING a.balance <> COALESCE(SUM(l.amount), 0)
                        """)
                .query(String.class).list();
        assertThat(unreconciled).as("accounts whose balance differs from their ledger").isEmpty();

        List<String> unbalanced = jdbc.sql("""
                        SELECT t.id
                        FROM transactions t
                        LEFT JOIN ledger_entries l ON l.transaction_id = t.id
                        GROUP BY t.id, t.type, t.status, t.amount
                        HAVING t.status = 'APPLIED' AND COALESCE(SUM(l.amount), 0) <> CASE t.type
                                   WHEN 'CREDIT' THEN t.amount
                                   WHEN 'DEBIT' THEN -t.amount
                                   ELSE 0 END
                            OR t.status = 'APPLIED' AND t.type = 'TRANSFER' AND count(l.id) <> 2
                            OR t.status <> 'APPLIED' AND count(l.id) <> 0
                        """)
                .query(String.class).list();
        assertThat(unbalanced).as("transactions whose ledger entries do not match their outcome").isEmpty();

        assertThat(jdbc.sql("SELECT count(*) FROM transactions WHERE status = 'PENDING'").query(Long.class).single())
                .as("transactions left in PENDING")
                .isZero();
    }
}
