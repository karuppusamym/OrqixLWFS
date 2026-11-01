package com.orqix.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class NodeRoleGuardTest {

    private static MockEnvironment profiles(String... names) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(names);
        return environment;
    }

    @Test
    void noRoleProfileIsAllInOne() {
        assertThat(NodeRoleGuard.role(profiles("prod"))).isEqualTo(NodeRoleGuard.Role.ALL);
        assertThat(NodeRoleGuard.role(profiles())).isEqualTo(NodeRoleGuard.Role.ALL);
    }

    @Test
    void apiAndWorkerProfilesSelectTheirRole() {
        assertThat(NodeRoleGuard.role(profiles("prod", "api"))).isEqualTo(NodeRoleGuard.Role.API);
        assertThat(NodeRoleGuard.role(profiles("prod", "worker"))).isEqualTo(NodeRoleGuard.Role.WORKER);
        assertThat(NodeRoleGuard.role(profiles("migrate"))).isEqualTo(NodeRoleGuard.Role.MIGRATE);
    }

    @Test
    void contradictoryRolesAreRefused() {
        assertThatThrownBy(() -> NodeRoleGuard.role(profiles("prod", "api", "worker")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'api' and 'worker'");
        assertThatThrownBy(() -> NodeRoleGuard.role(profiles("migrate", "api")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'migrate'");
        assertThatThrownBy(() -> NodeRoleGuard.role(profiles("migrate", "worker")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'migrate'");
    }
}
