package com.fluxbank.integration;

import com.fluxbank.account.repository.AccountRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

class PostgresSmokeIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private PostgreSQLContainer<?> postgres;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Autowired
    private AccountRepository accounts;

    @Test
    void applicationUsesFlywayMigratedPostgres() throws Exception {
        assertTrue(postgres.isRunning());
        try (var connection = dataSource.getConnection()) {
            assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
            assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL());
        }
        flyway.validate();
        assertNotNull(flyway.info().current());
        assertEquals(0, flyway.info().pending().length);
        assertDoesNotThrow(() -> accounts.count());
    }
}
