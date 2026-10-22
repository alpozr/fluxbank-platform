package com.fluxbank.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Shares a Spring-managed PostgreSQL container through the cached test context.
 * No outer test transaction: subclasses must choose isolation appropriate to their scenario.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Import(PostgresTestConfiguration.class)
public abstract class PostgresIntegrationTest {
}
