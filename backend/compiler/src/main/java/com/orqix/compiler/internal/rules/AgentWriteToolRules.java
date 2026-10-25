package com.orqix.compiler.internal.rules;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.orqix.compiler.CompileContext;
import com.orqix.compiler.internal.json.StrictJson;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.rules.Connections.Pinned;
import com.orqix.compiler.internal.rules.Connections.Status;

import tools.jackson.databind.JsonNode;

/**
 * Agent write tools (IN04, S75). A "Call a system" step whose agent operation declares write tools is publishable only
 * when every one of them is approved for this workflow key, connection version, operation and tool, and when each
 * tool's adapter exists, is enabled, has the tool's kind and approves every fixed recipient. Declaring a tool, or having
 * the adapter, authorizes nothing; the runtime checks all of this again before every write.
 */
final class AgentWriteToolRules {

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]{1,64}@([A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63})+)$");

    private AgentWriteToolRules() {
    }

    static void check(RuleContext rc) {
        for (Node node : rc.definition().nodes()) {
            if (node instanceof Node.Http http && !rc.graph().isShadowed(node)) {
                check(rc, http);
            }
        }
    }

    private static void check(RuleContext rc, Node.Http http) {
        Pinned pinned = rc.connections().pinned(http.connectionRef());
        if (pinned == null || pinned.status() != Status.OK
                || !"agent".equals(pinned.definition().path("kind").asString(""))) {
            return;
        }
        JsonNode operation = rc.connections().operation(http.connectionRef(), http.operation());
        if (operation == null) {
            return;
        }
        JsonNode writeTools = operation.path("agent").path("writeTools");
        if (!writeTools.isArray() || writeTools.isEmpty()) {
            return;
        }
        String path = http.configPath("operation");
        if (http.undoHandler()) {
            rc.issues().blocker("WF_AGENT_WRITE_TOOL_PLACEMENT", path, http.id(), null, RuleContext.q(http)
                    + " is an automatic undo step, and its agent can send messages. Undo steps may only use agents"
                    + " without write tools.");
        }
        Set<String> readTools = new HashSet<>();
        operation.path("agent").path("tools").forEach(t -> readTools.add(t.path("name").asString("")));
        int version = pinned.dependency().definitionVersion();
        for (JsonNode tool : writeTools) {
            String name = tool.path("name").asString("");
            String label = tool.path("label").asString(name);
            if (readTools.contains(name)) {
                rc.issues().blocker("WF_AGENT_WRITE_TOOL_INVALID", path, http.id(), null, "The write tool "
                        + RuleContext.q(name) + " has the same name as a read tool of the agent. Every tool name must"
                        + " differ.");
            }
            adapter(rc, http, path, tool, label);
            CompileContext.WriteToolApproval needed = new CompileContext.WriteToolApproval(
                    rc.definition().workflowKey(), http.connectionRef(), version, http.operation(), name);
            if (!rc.workspace().writeToolApprovals().contains(needed)) {
                rc.issues().blocker("WF_AGENT_WRITE_TOOL_UNAPPROVED", path, http.id(), null, RuleContext.q(http)
                        + " lets the agent send messages with " + RuleContext.q(label) + " (connection version " + version
                        + "), which has no current approval for this workflow: it was never approved, or its approval"
                        + " was revoked or has expired. A person allowed to approve agent write tools must approve it"
                        + " first.");
            }
        }
    }

    private static void adapter(RuleContext rc, Node.Http http, String path, JsonNode tool, String label) {
        String key = tool.path("adapter").asString("");
        String kind = tool.path("kind").asString("");
        CompileContext.AdapterInfo adapter = rc.workspace().adapters().get(key);
        if (adapter == null) {
            rc.issues().blocker("WF_AGENT_WRITE_TOOL_ADAPTER", path, http.id(), null, "The write tool "
                    + RuleContext.q(label) + " sends through the adapter " + RuleContext.q(key)
                    + ", which does not exist in this workspace.");
            return;
        }
        if (!adapter.kind().equals(kind)) {
            rc.issues().blocker("WF_AGENT_WRITE_TOOL_ADAPTER", path, http.id(), null, "The write tool "
                    + RuleContext.q(label) + " expects a " + kind + " adapter, but " + RuleContext.q(adapter.name())
                    + " is a " + adapter.kind() + " adapter.");
            return;
        }
        if (!adapter.enabled()) {
            rc.issues().blocker("WF_AGENT_WRITE_TOOL_ADAPTER", path, http.id(), null, "The write tool "
                    + RuleContext.q(label) + " sends through " + RuleContext.q(adapter.name()) + ", which is disabled.");
        }
        if (!"email".equals(kind)) {
            return;
        }
        JsonNode config = StrictJson.parse(adapter.configJson());
        Set<String> allowed = new LinkedHashSet<>();
        config.path("allowedRecipientDomains").forEach(d -> allowed.add(d.asString()));
        int max = config.path("maxRecipients").asInt(5);
        JsonNode to = tool.path("to");
        if (to.size() > max) {
            rc.issues().blocker("WF_AGENT_WRITE_TOOL_RECIPIENT", path, http.id(), null, "The write tool "
                    + RuleContext.q(label) + " has " + to.size() + " recipients; the adapter allows at most " + max + ".");
        }
        for (JsonNode address : to) {
            Matcher m = EMAIL.matcher(address.asString(""));
            String domain = m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : null;
            if (domain == null || !allowed.contains(domain)) {
                rc.issues().blocker("WF_AGENT_WRITE_TOOL_RECIPIENT", path, http.id(), null, "The write tool "
                        + RuleContext.q(label) + " sends to " + RuleContext.q(address.asString("")) + ", whose domain the"
                        + " adapter " + RuleContext.q(adapter.name()) + " does not approve.");
            }
        }
    }
}
