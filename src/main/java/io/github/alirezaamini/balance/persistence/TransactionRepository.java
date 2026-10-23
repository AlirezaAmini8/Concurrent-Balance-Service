package io.github.alirezaamini.balance.persistence;

import io.github.alirezaamini.balance.domain.RejectionReason;
import io.github.alirezaamini.balance.domain.Transaction;
import io.github.alirezaamini.balance.domain.TransactionRequest;
import io.github.alirezaamini.balance.domain.TransactionStatus;
import io.github.alirezaamini.balance.domain.TransactionType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class TransactionRepository {

    private static final RowMapper<Transaction> TRANSACTION_MAPPER = (rs, rowNum) -> {
        String reason = rs.getString("rejection_reason");
        return new Transaction(
                rs.getString("id"),
                TransactionType.valueOf(rs.getString("type")),
                rs.getString("source_account_id"),
                rs.getString("destination_account_id"),
                rs.getLong("amount"),
                TransactionStatus.valueOf(rs.getString("status")),
                reason == null ? null : RejectionReason.valueOf(reason),
                rs.getTimestamp("created_at").toInstant());
    };

    private final JdbcClient jdbc;

    public TransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean claim(TransactionRequest request) {
        int inserted = jdbc.sql("""
                INSERT INTO transactions (id, type, source_account_id, destination_account_id, amount, status)
                VALUES (:id, :type, :source, :destination, :amount, 'PENDING')
                ON CONFLICT (id) DO NOTHING
                """)
                .param("id", request.transactionId())
                .param("type", request.type().name())
                .param("source", request.sourceAccountId())
                .param("destination", request.destinationAccountId())
                .param("amount", request.amount())
                .update();
        return inserted == 1;
    }

    public Transaction complete(String id, TransactionStatus status, RejectionReason reason) {
        return jdbc.sql("""
                UPDATE transactions
                SET status = :status, rejection_reason = :reason
                WHERE id = :id
                RETURNING *
                """)
                .param("id", id)
                .param("status", status.name())
                .param("reason", reason == null ? null : reason.name())
                .query(TRANSACTION_MAPPER)
                .single();
    }

    public Optional<Transaction> findById(String id) {
        return jdbc.sql("SELECT * FROM transactions WHERE id = :id")
                .param("id", id)
                .query(TRANSACTION_MAPPER)
                .optional();
    }
}
