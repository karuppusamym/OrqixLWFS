package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** RL03 separation of duties: which references are valid (docs/build/S41). */
class SeparationRulesTest {

    private static boolean invalid(String node, String... refs) {
        ObjectNode d = TestSupport.purchase();
        for (JsonNode n : d.path("nodes")) {
            if (node.equals(n.path("id").asString())) {
                var list = ((ObjectNode) n.path("config")).putArray("separateFrom");
                for (String ref : refs) list.add(ref);
            }
        }
        return Compilers.workflowCompiler().validate(d.toString(), SimulatorFixtures.context().workspace()).issues().stream()
                .anyMatch(i -> i.code().equals("WF_SEPARATION_INVALID"));
    }

    @Test
    void anEarlierApprovalOnEveryPathIsAccepted() {
        assertThat(invalid("finance", "manager")).isFalse();
    }

    @Test
    void unknownNonApprovalSelfAndLaterStepsAreRejected() {
        assertThat(invalid("finance", "nowhere")).as("unknown").isTrue();
        assertThat(invalid("finance", "amountPolicy")).as("not an approval").isTrue();
        assertThat(invalid("finance", "finance")).as("itself").isTrue();
        assertThat(invalid("manager", "finance")).as("decided later, not on every path before").isTrue();
    }
}
