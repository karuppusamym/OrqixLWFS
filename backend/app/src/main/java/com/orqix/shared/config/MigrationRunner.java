package com.orqix.shared.config;

import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Migration job (profile {@code migrate}, S1 section 5): Flyway (schema orqix) and the Flowable schema update (schema
 * flowable) have run during startup as the DDL owner; this runner grants the runtime role DML on the engine tables
 * (in addition to any default privileges set up by the database bootstrap), then the application exits.
 */
@Component
@Profile("migrate")
public class MigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);
    private static final Pattern ROLE = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");

    private final JdbcClient jdbc;
    private final Environment environment;
    private final OrqixProperties properties;

    public MigrationRunner(JdbcClient jdbc, Environment environment, OrqixProperties properties) {
        this.jdbc = jdbc;
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String role = environment.getProperty("spring.flyway.placeholders.app_role", properties.migration().appRole());
        if (!ROLE.matcher(role).matches()) {
            throw new IllegalStateException("Invalid application role name");
        }
        jdbc.sql("GRANT USAGE ON SCHEMA flowable TO " + role).update();
        jdbc.sql("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA flowable TO " + role).update();
        jdbc.sql("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA flowable TO " + role).update();
        Integer engineTables = jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'flowable'")
                .query(Integer.class).single();
        Integer appTables = jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'orqix'")
                .query(Integer.class).single();
        log.info("Migration complete: {} tables in schema orqix, {} engine tables in schema flowable; DML granted to {}",
                appTables, engineTables, role);
    }
}
