package io.github.alirezaamini.balance.persistence;

import io.github.alirezaamini.balance.domain.Account;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class AccountRepository {

    private static final RowMapper<Account> ACCOUNT_MAPPER =
            (rs, rowNum) -> new Account(rs.getString("id"), rs.getLong("balance"));

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return {@code false} if an account with this id already exists
     */
    public boolean insert(Account account) {
        int inserted = jdbc.sql("""
                        INSERT INTO accounts (id, balance)
                        VALUES (:id, :balance)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", account.id())
                .param("balance", account.balance())
                .update();
        return inserted == 1;
    }

    public Optional<Account> findById(String id) {
        return jdbc.sql("SELECT id, balance FROM accounts WHERE id = :id")
                .param("id", id)
                .query(ACCOUNT_MAPPER)
                .optional();
    }

    /**
     * Reads the account and takes a row-level write lock on it, held until the surrounding
     * database transaction ends. Concurrent writers of the same account queue up behind it;
     * other accounts are not affected.
     */
    public Optional<Account> findByIdForUpdate(String id) {
        return jdbc.sql("SELECT id, balance FROM accounts WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(ACCOUNT_MAPPER)
                .optional();
    }

    public void updateBalance(Account account) {
        jdbc.sql("UPDATE accounts SET balance = :balance, updated_at = now() WHERE id = :id")
                .param("id", account.id())
                .param("balance", account.balance())
                .update();
    }
}
