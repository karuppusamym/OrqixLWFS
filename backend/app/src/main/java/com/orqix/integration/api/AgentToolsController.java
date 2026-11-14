package com.orqix.integration.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.integration.agenttools.AgentDashboard;
import com.orqix.integration.agenttools.AgentKillSwitches;
import com.orqix.integration.agenttools.AgentTests;
import com.orqix.integration.agenttools.AgentTrace;
import com.orqix.integration.agenttools.McpToolPins;

/** Agent tools (docs/build/S135; contracts/openapi.yaml, tag AgentTools): pins, kill switches, trace and tests. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class AgentToolsController {

    private final CallerResolver callers;
    private final McpToolPins pins;
    private final AgentKillSwitches switches;
    private final AgentTrace trace;
    private final AgentTests tests;
    private final AgentDashboard dashboard;

    public AgentToolsController(CallerResolver callers, McpToolPins pins, AgentKillSwitches switches, AgentTrace trace,
            AgentTests tests, AgentDashboard dashboard) {
        this.dashboard = dashboard;
        this.callers = callers;
        this.pins = pins;
        this.switches = switches;
        this.trace = trace;
        this.tests = tests;
    }

    @GetMapping("/connections/{connectionKey}/tool-snapshot")
    public McpToolPins.Snapshot getMcpToolSnapshot(@PathVariable UUID workspaceId, @PathVariable String connectionKey) {
        return pins.snapshot(callers.current(), workspaceId, connectionKey);
    }

    @PostMapping("/connections/{connectionKey}/tool-snapshot")
    public McpToolPins.Snapshot takeMcpToolSnapshot(@PathVariable UUID workspaceId, @PathVariable String connectionKey) {
        return pins.take(callers.current(), workspaceId, connectionKey);
    }

    @GetMapping("/agent-kill-switches")
    public List<AgentKillSwitches.Switch> listAgentKillSwitches(@PathVariable UUID workspaceId) {
        return switches.list(callers.current(), workspaceId);
    }

    @PutMapping("/agent-kill-switches/{scope}/{connectionKey}")
    public AgentKillSwitches.Switch setAgentKillSwitch(@PathVariable UUID workspaceId, @PathVariable String scope,
            @PathVariable String connectionKey, @Valid @RequestBody AgentKillSwitches.SetRequest request) {
        return switches.set(callers.current(), workspaceId, scope, connectionKey, request);
    }

    @GetMapping("/agent-trace-settings")
    public AgentTrace.Settings getAgentTraceSettings(@PathVariable UUID workspaceId) {
        return trace.getSettings(callers.current(), workspaceId);
    }

    @PutMapping("/agent-trace-settings")
    public AgentTrace.Settings setAgentTraceSettings(@PathVariable UUID workspaceId,
            @Valid @RequestBody AgentTrace.SettingsRequest request) {
        return trace.setSettings(callers.current(), workspaceId, request);
    }

    @GetMapping("/runs/{runId}/agent-trace")
    public AgentTrace.Trace getAgentTrace(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @RequestParam(name = "nodeId", required = false) String nodeId) {
        return trace.trace(callers.current(), workspaceId, runId, nodeId);
    }

    @GetMapping("/connections/{connectionKey}/agent-tests")
    public AgentTests.Suite getAgentTestSuite(@PathVariable UUID workspaceId, @PathVariable String connectionKey) {
        return tests.get(callers.current(), workspaceId, connectionKey);
    }

    @PutMapping("/connections/{connectionKey}/agent-tests")
    public AgentTests.Suite saveAgentTestSuite(@PathVariable UUID workspaceId, @PathVariable String connectionKey,
            @Valid @RequestBody AgentTests.SaveRequest request) {
        return tests.save(callers.current(), workspaceId, connectionKey, request);
    }

    @PostMapping("/connections/{connectionKey}/agent-tests/run")
    public AgentTests.Suite runAgentTestSuite(@PathVariable UUID workspaceId, @PathVariable String connectionKey) {
        return tests.run(callers.current(), workspaceId, connectionKey);
    }

    @GetMapping("/agent-dashboard")
    public AgentDashboard.Dashboard getAgentDashboard(@PathVariable UUID workspaceId,
            @RequestParam(name = "days", required = false) Integer days) {
        return dashboard.dashboard(callers.current(), workspaceId, days);
    }
}
