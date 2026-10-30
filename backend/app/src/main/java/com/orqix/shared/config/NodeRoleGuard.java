package com.orqix.shared.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Runtime role of this pod (docs/adr/0004): all-in-one by default, or {@code api} / {@code worker} by profile.
 * Runs as a bean factory post-processor so a contradictory combination stops startup before Flyway, the engine or
 * any scheduler is created.
 */
@Component
public class NodeRoleGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final Logger LOG = LoggerFactory.getLogger(NodeRoleGuard.class);

    public enum Role { ALL, API, WORKER, MIGRATE }

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        Role role = role(environment);
        LOG.info("Orqix node role: {}", role.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** @throws IllegalStateException when the active profiles name more than one role */
    public static Role role(Environment environment) {
        boolean api = environment.matchesProfiles("api");
        boolean worker = environment.matchesProfiles("worker");
        boolean migrate = environment.matchesProfiles("migrate");
        if (api && worker) {
            throw new IllegalStateException("Profiles 'api' and 'worker' cannot both be active; "
                    + "use neither for an all-in-one pod (docs/adr/0004)");
        }
        if (migrate && (api || worker)) {
            throw new IllegalStateException("Profile 'migrate' cannot be combined with 'api' or 'worker' (docs/adr/0004)");
        }
        if (migrate) {
            return Role.MIGRATE;
        }
        return api ? Role.API : worker ? Role.WORKER : Role.ALL;
    }
}
