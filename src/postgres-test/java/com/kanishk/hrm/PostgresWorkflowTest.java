package com.kanishk.hrm;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class PostgresWorkflowTest extends HrmWorkflowTest {
    private static EmbeddedPostgres database;

    @DynamicPropertySource
    static void freshDatabase(DynamicPropertyRegistry properties) {
        try {
            database = EmbeddedPostgres.builder().setServerConfig("listen_addresses", "127.0.0.1").start();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        properties.add("spring.datasource.url", () -> database.getJdbcUrl("postgres", "postgres"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> "postgres");
        properties.add("spring.datasource.password", () -> "");
    }

    @AfterAll
    static void stopDatabase() throws IOException {
        if (database != null) database.close();
    }
}
