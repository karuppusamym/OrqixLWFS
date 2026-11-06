package com.orqix.integration.agenttools;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.orqix.integration.AgentWaitTools;
import com.orqix.integration.ConnectionService;
import com.orqix.integration.EffectDispatcher;

/**
 * The services an agent needs beyond the model and its own MCP binding (S135): registered tool servers and their pins,
 * kill switches, the opt-in trace, decision tables, connection operations through the effect dispatcher, and the
 * workflow lookup of the runtime. Held lazily by the executor, which the dispatcher itself depends on.
 */
@Component
public class AgentToolkit {

    private final McpServers servers;
    private final McpToolPins pins;
    private final AgentKillSwitches switches;
    private final AgentTrace trace;
    private final AgentDecisionTools decisions;
    private final ConnectionService connections;
    private final ObjectProvider<EffectDispatcher> dispatcher;
    private final ObjectProvider<AgentWaitTools> waits;

    public AgentToolkit(McpServers servers, McpToolPins pins, AgentKillSwitches switches, AgentTrace trace,
            AgentDecisionTools decisions, ConnectionService connections, ObjectProvider<EffectDispatcher> dispatcher,
            ObjectProvider<AgentWaitTools> waits) {
        this.servers = servers;
        this.pins = pins;
        this.switches = switches;
        this.trace = trace;
        this.decisions = decisions;
        this.connections = connections;
        this.dispatcher = dispatcher;
        this.waits = waits;
    }

    public McpServers servers() {
        return servers;
    }

    public McpToolPins pins() {
        return pins;
    }

    public AgentKillSwitches switches() {
        return switches;
    }

    public AgentTrace trace() {
        return trace;
    }

    public AgentDecisionTools decisions() {
        return decisions;
    }

    public ConnectionService connections() {
        return connections;
    }

    public EffectDispatcher dispatcher() {
        return dispatcher.getObject();
    }

    public AgentWaitTools waits() {
        return waits.getIfAvailable(() -> AgentWaitTools.NONE);
    }
}
