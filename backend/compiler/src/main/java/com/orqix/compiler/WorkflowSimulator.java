package com.orqix.compiler;

/**
 * Dry run of a canonical workflow definition ("Test this workflow", S4 section 13). Walks the definition for a sample
 * request and a scenario that says what happens at human steps and external calls. Pure: no engine, no I/O, no clock
 * other than the date in the {@link SimulationContext}. It uses the same form validation, decision evaluation and
 * condition semantics as production, but it is not the engine: it does not test connection availability, people or
 * timing. Implementations are immutable and thread-safe.
 */
public interface WorkflowSimulator {

    /**
     * @param definitionJson canonical workflow definition (draft or release)
     * @param inputJson      the sample request, a JSON object of form answers
     * @param scenarioJson   the scenario (see the simulate API of the contract); null or blank means the defaults
     * @throws IllegalArgumentException when the scenario is not a JSON object or has a member of the wrong type
     */
    SimulationResult simulate(String definitionJson, String inputJson, String scenarioJson, SimulationContext context);
}
