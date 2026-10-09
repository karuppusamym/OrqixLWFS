package com.orqix.resilience;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import com.orqix.OrqixApplication;

/**
 * A PostgreSQL container of its own for the resilience suite (same image, bootstrap and migration path as
 * {@code com.orqix.support.TestDatabase}, which is left untouched). The separate database keeps the application
 * contexts of the regular integration tests, which stay cached and keep their job executors running, from competing
 * for the jobs of these tests: with two or more executors on one database, every executor in the JVM would otherwise
 * count as another cluster node.
 */
public final class ResilienceDatabase {

    public static final String APP_USER = "orqix_app";
    public static final String APP_PASSWORD = "app-test";
    private static final String MIGRATOR_USER = "orqix_migrator";
    private static final String MIGRATOR_PASSWORD = "migrator-test";

    private static PostgreSQLContainer container;
    private static boolean migrated;

    private ResilienceDatabase() {
    }

    public static synchronized PostgreSQLContainer container() {
        if (container == null) {
            PostgreSQLContainer pg = new PostgreSQLContainer("postgres:18-alpine")
                    .withDatabaseName("orqix")
                    .withUsername("postgres")
                    .withPassword("postgres")
                    .withCopyFileToContainer(MountableFile.forClasspathResource("db/test-init.sql"),
                            "/docker-entrypoint-initdb.d/01-orqix-init.sql")
                    .withCommand("postgres", "-c", "max_connections=300", "-c", "fsync=off",
                            "-c", "synchronous_commit=off", "-c", "full_page_writes=off");
            pg.start();
            container = pg;
        }
        return container;
    }

    public static String jdbcUrl() {
        return container().getJdbcUrl();
    }

    /** Properties for an application context connecting as the DML-only runtime role (migrates on first use). */
    public static synchronized Map<String, Object> appProperties() {
        if (!migrated) {
            migrate();
            migrated = true;
        }
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", jdbcUrl());
        properties.put("spring.datasource.username", APP_USER);
        properties.put("spring.datasource.password", APP_PASSWORD);
        return properties;
    }

    /** Sessions of the runtime role on the database (superuser view), e.g. to prove that a context released its pool. */
    public static int appConnections() {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(jdbcUrl(), "postgres", "postgres");
                java.sql.Statement st = c.createStatement();
                java.sql.ResultSet rs = st.executeQuery(
                        "SELECT count(*) FROM pg_stat_activity WHERE usename = 'orqix_app' AND datname = 'orqix'")) {
            rs.next();
            return rs.getInt(1);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void migrate() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", jdbcUrl());
        properties.put("orqix.db.migrator-user", MIGRATOR_USER);
        properties.put("orqix.db.migrator-password", MIGRATOR_PASSWORD);
        properties.put("spring.flyway.locations", "classpath:db/migration,classpath:db/dev-seed");
        properties.put("orqix.dev-seed.stub-base-url", "http://localhost:8095");
        properties.put("logging.structured.format.console", "");
        String[] args = properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(OrqixApplication.class)
                .profiles("test", "migrate").run(args)) {
            // closing the context ends the migration job
        }
    }
}
