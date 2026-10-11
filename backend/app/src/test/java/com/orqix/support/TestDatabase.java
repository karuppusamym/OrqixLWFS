package com.orqix.support;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import com.orqix.OrqixApplication;

/**
 * One PostgreSQL container per test JVM (same major as the target, S9 section 1), bootstrapped like
 * deploy/compose (roles orqix_migrator / orqix_app, schemas orqix / flowable) and migrated once by running the real
 * {@code migrate} profile as the DDL owner, including the dev seed. Application contexts then connect as the DML-only
 * runtime role, exactly like pods.
 */
public final class TestDatabase {

    public static final String APP_USER = "orqix_app";
    public static final String APP_PASSWORD = "app-test";
    public static final String MIGRATOR_USER = "orqix_migrator";
    public static final String MIGRATOR_PASSWORD = "migrator-test";

    private static PostgreSQLContainer container;
    private static boolean migrated;

    private TestDatabase() {
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

    /** Owner connection only for constructing legacy fixtures; application permissions remain unchanged. */
    public static org.springframework.jdbc.core.simple.JdbcClient legacyFixtureJdbc() {
        ensureMigrated();
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                jdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
        return org.springframework.jdbc.core.simple.JdbcClient.create(source);
    }

    /** Runs the migrate profile once (Flyway incl. dev seed + Flowable schema + grants). */
    public static synchronized void ensureMigrated() {
        if (migrated) {
            return;
        }
        try (ConfigurableApplicationContext ignored = migrate(Map.of())) {
            migrated = true;
        }
    }

    /** Runs the migrate profile against the shared database with extra properties; caller closes the context. */
    public static ConfigurableApplicationContext migrate(Map<String, Object> extra) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", jdbcUrl());
        properties.put("orqix.db.migrator-user", MIGRATOR_USER);
        properties.put("orqix.db.migrator-password", MIGRATOR_PASSWORD);
        properties.put("spring.flyway.locations", "classpath:db/migration,classpath:db/dev-seed");
        properties.put("orqix.dev-seed.stub-base-url", "http://localhost:8095");
        properties.put("logging.structured.format.console", "");
        properties.putAll(extra);
        // Command-line arguments: highest precedence, above the profile YAML files.
        String[] args = properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(OrqixApplication.class)
                .profiles("test", "migrate")
                .run(args);
    }

    /** Properties for an application context connecting as the runtime role. */
    public static Map<String, Object> appProperties() {
        ensureMigrated();
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", jdbcUrl());
        properties.put("spring.datasource.username", APP_USER);
        properties.put("spring.datasource.password", APP_PASSWORD);
        return properties;
    }
}
