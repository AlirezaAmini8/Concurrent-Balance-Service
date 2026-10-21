package io.github.alirezaamini.balance.persistence;

import io.github.alirezaamini.balance.domain.LedgerEntry;
import io.github.alirezaamini.balance.domain.LedgerEntryType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class LedgerRepository {

    private static final RowMapper<LedgerEntry> ENTRY_MAPPER = (rs, rowNum) -> new LedgerEntry(
            rs.getLong("id"),
            rs.getString("account_id"),
            rs.getString("transaction_id"),
            LedgerEntryType.valueOf(rs.getString("entry_type")),
            rs.getLong("amount"),
            rs.getLong("balance_after"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void append(String accountId, String transactionId, LedgerEntryType type,
                       long signedAmount, long balanceAfter) {
        jdbc.sql("""
                        INSERT INTO ledger_entries (account_id, transaction_id, entry_type, amount, balance_after)
                        VALUES (:accountId, :transactionId, :type, :amount, :balanceAfter)
                        """)
                .param("accountId", accountId)
                .param("transactionId", transactionId)
                .param("type", type.name())
                .param("amount", signedAmount)
                .param("balanceAfter", balanceAfter)
                .update();
    }

    public List<LedgerEntry> findByAccountId(String accountId) {
        return jdbc.sql("SELECT * FROM ledger_entries WHERE account_id = :accountId ORDER BY id")
                .param("accountId", accountId)
                .query(ENTRY_MAPPER)
                .list();
    }
}
