package com.orqix.migration.camunda;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class IdentifiersTest {
    @Test
    void canonicalNodeIdsKeepValidSourceNamesAndNormalizeUnsupportedOnes() {
        assertThat(Identifiers.sanitize("Task_Approval-7", false)).isEqualTo("Task_Approval-7");
        for (String source : List.of("7task", "task__approval", "task with spaces", "é", "_task", "", "a".repeat(100))) {
            assertThat(Identifiers.sanitize(source, false)).matches("^(?!.*__)[A-Za-z][A-Za-z0-9_-]{0,63}$");
        }
        assertThat(Identifiers.sanitize(null, false)).isEqualTo("node");
    }

    @Test
    void workflowKeysMeetTheSchema() {
        for (String source : List.of("Task_Approval-7", "7task", "A", "---", "a".repeat(63) + "-tail")) {
            assertThat(Identifiers.sanitize(source, true)).matches("^[a-z][a-z0-9-]{0,62}[a-z0-9]$");
        }
    }

    @Test
    void collidingAndTruncatedSourceIdsGetDistinctDeterministicNames() {
        Set<String> used = new HashSet<>();
        String base = "a".repeat(64);
        assertThat(Identifiers.unique(base, used)).isEqualTo(base);
        assertThat(Identifiers.unique(base, used)).isEqualTo("a".repeat(62) + "-2");
        assertThat(Identifiers.unique(base, used)).isEqualTo("a".repeat(62) + "-3");
        assertThat(used).hasSize(3);
    }
}
