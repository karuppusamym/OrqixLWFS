package com.orqix.identity.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.orqix.shared.Ids;
import com.orqix.shared.config.OrqixProperties;

/**
 * At startup, IdP group values in {@code orqix.identity.bootstrap-platform-admin-groups} receive a platform
 * PLATFORM_ADMIN binding with {@code source = bootstrap} (S6 section 1). Idempotent across pods and restarts.
 */
@Component
@Profile("!migrate")
public class BootstrapPlatformAdmins implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapPlatformAdmins.class);

    private final JdbcClient jdbc;
    private final OrqixProperties properties;

    public BootstrapPlatformAdmins(JdbcClient jdbc, OrqixProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String group : properties.identity().bootstrapPlatformAdminGroups()) {
            int inserted = jdbc.sql("""
                    INSERT INTO role_binding (id, scope_type, role, principal_type, principal_value, source)
                    VALUES (:id, 'platform', 'PLATFORM_ADMIN', 'idpGroup', :group, 'bootstrap')
                    ON CONFLICT DO NOTHING
                    """)
                    .param("id", Ids.newId()).param("group", group).update();
            if (inserted > 0) {
                log.info("Bootstrap platform administrators: IdP group {}", group);
            }
        }
    }
}
