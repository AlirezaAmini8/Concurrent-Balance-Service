package io.github.alirezaamini.balance.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for tests that need the full application against a real PostgreSQL.
 * <p>
 * Row locks, ON CONFLICT semantics and constraint checks are the heart of the design, so they are tested
 * against the real database rather than an in-memory substitute. The container is started once per JVM
 * and shared by all test classes; tables are truncated before every test.
 */
@SpringBootTest
public abstract class PostgresIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("TRUNCATE transactions, accounts").update();
    }
}
