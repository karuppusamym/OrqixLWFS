package com.orqix.compiler.internal.rules;

import java.time.LocalDate;
import java.util.*;
import com.orqix.compiler.BusinessCalendar;
import com.orqix.compiler.CompileContext;
import tools.jackson.databind.JsonNode;

/** Opt-in acceptance constraints. Broader platform workflows keep their existing behavior. */
public final class Tier1Rules {
    private Tier1Rules() {}
    public static void validate(JsonNode root, CompileContext context, Issues issues) {
        boolean tier1 = "tier1".equals(root.path("acceptanceProfile").asString());
        Set<String> roles = new HashSet<>();
        Map<String, JsonNode> nodes = new HashMap<>();
        Map<String, List<String>> edges = new HashMap<>();
        for (JsonNode n : root.path("nodes")) nodes.put(n.path("id").asString(), n);
        for (JsonNode e : root.path("transitions")) edges.computeIfAbsent(e.path("from").asString(), k -> new ArrayList<>()).add(e.path("to").asString());
        for (int i = 0; i < root.path("nodes").size(); i++) {
            JsonNode n = root.path("nodes").get(i), c = n.path("config");
            String id = n.path("id").asString(), type = n.path("type").asString(), path = "/nodes/" + i;
            if (c.has("dueAfterHours") && c.has("dueAfterMinutes"))
                issues.blocker("WF_SLA_DURATION_CONFLICT", path + "/config/dueAfterMinutes", id, null, "Choose hours or minutes, not both.");
            for (var entry : c.path("notifications").properties()) validateMail(entry.getValue(), path + "/config/notifications/" + entry.getKey(), id, context, issues, false);
            if (!tier1) continue;
            if (!Set.of("start", "end", "approval", "decision", "branch", "http", "notification").contains(type)
                    || c.has("multiInstance") || c.has("perItem") || c.has("timeoutAfter") || c.has("correctable")
                    || c.has("fieldAccess") || c.has("capture"))
                issues.blocker("WF_TIER1_CONSTRUCT", path, id, null, "This construct requires the broader platform profile.");
            if ("approval".equals(type)) {
                roles.add(c.path("candidateGroupRef").asString());
                c.path("requiredSkills").forEach(v -> roles.add(v.asString()));
                if ((!c.has("dueAfterMinutes") && !c.has("dueAfterHours")) || !c.has("calendar"))
                    issues.blocker("WF_TIER1_SLA_REQUIRED", path + "/config", id, null, "Tier-1 tasks need a positive duration and a working calendar.");
                JsonNode cal = c.path("calendar");
                try {
                    if (cal.path("revision").asString("").isBlank() || !cal.has("validFrom")) throw new IllegalArgumentException("Tier-1 calendars need revision and validFrom.");
                    BusinessCalendar.read(cal).validateBoundaries(LocalDate.parse(cal.path("validFrom").asString()));
                } catch (IllegalArgumentException | java.time.DateTimeException e) {
                    issues.blocker("WF_TIER1_CALENDAR_INVALID", path + "/config/calendar", id, null, e.getMessage());
                }
                if (!c.has("notifications")) issues.blocker("WF_TIER1_NOTIFICATIONS_REQUIRED", path + "/config", id, null, "Configure assignment, completion, rejection and overdue email templates.");
            }
        }
        if (root.has("terminalNotification")) validateMail(root.path("terminalNotification"), "/terminalNotification", null, context, issues, true);
        if (!tier1) return;
        JsonNode assessment=root.path("tier1Assessment");
        if (assessment.path("businessOwner").asString("").isBlank() || !assessment.path("requirementsReviewed").asBoolean(false)
                || !"standard".equals(assessment.path("regulatoryClassification").asString())
                || assessment.path("expectedDurationDays").asInt(0)<1 || assessment.path("expectedDurationDays").asInt()>30
                || assessment.path("peakConcurrentUsers").asInt(0)<1 || assessment.path("peakConcurrentUsers").asInt()>50
                || assessment.path("storedCases").asInt(0)<1 || assessment.path("storedCases").asInt()>10000)
            issues.blocker("WF_TIER1_ASSESSMENT_REQUIRED", "/tier1Assessment", null, null,
                    "Record a reviewed business owner, standard classification, duration at most 30 days and a pilot within 50 users/10000 stored cases. Other candidates need broader qualification.");
        if (root.path("nodes").size() > 10) issues.blocker("WF_TIER1_STATE_LIMIT", "/nodes", null, null, "Tier-1 supports at most 10 executable states, including start/end.");
        if (roles.size() > 5) issues.blocker("WF_TIER1_ROLE_LIMIT", "/nodes", null, null, "Tier-1 supports at most 5 distinct routing roles/groups.");
        if (!"deny".equals(root.path("security").path("selfApproval").asString())) issues.blocker("WF_TIER1_SELF_APPROVAL", "/security/selfApproval", null, null, "Tier-1 always denies requester self-approval.");
        for (JsonNode table : root.path("decisions")) {
            if (!"UNIQUE".equals(table.path("hitPolicy").asString())) issues.blocker("WF_TIER1_RULE_POLICY", "/decisions", null, null, "Tier-1 requires mutually exclusive UNIQUE rules.");
        }
        if (!root.has("terminalNotification")) issues.blocker("WF_TIER1_TERMINAL_NOTIFICATION", "/terminalNotification", null, null, "Configure the terminal case email.");
        Set<String> visiting = new HashSet<>(), done = new HashSet<>();
        for (String id : nodes.keySet()) {
            if (cycle(id, edges, visiting, done)) { issues.blocker("WF_TIER1_CYCLE", "/transitions", null, null, "Tier-1 must be acyclic."); break; }
        }
        if (!issues.hasBlockers()) for (String id : nodes.keySet()) {
            if (maxCalls(id, nodes, edges, new HashMap<>()) > 2) { issues.blocker("WF_TIER1_INTEGRATION_LIMIT", "/nodes", null, null, "Tier-1 allows at most 2 business integration calls per path."); break; }
        }
    }
    private static void validateMail(JsonNode template, String path, String id, CompileContext context, Issues issues, boolean terminal) {
        try { com.orqix.compiler.MailTemplates.validate(template); }
        catch (IllegalArgumentException e) { issues.blocker("WF_MAIL_TEMPLATE_INVALID", path, id, null, e.getMessage()); }
        for (JsonNode recipient : template.path("recipients")) {
            String kind=recipient.asString();
            if (terminal && Set.of("assignee","candidateGroup").contains(kind)) issues.blocker("WF_MAIL_RECIPIENT_INVALID", path, id, null, "Terminal email has no current task; use requester, supervisor or operators.");
            if ("supervisor".equals(kind)) {
                String group=template.path("supervisorGroupRef").asString("");
                if (!context.groups().containsKey(group) || context.groups().get(group).configuredMemberCount() < 1)
                    issues.blocker("WF_MAIL_GROUP_UNBOUND", path, id, null, "Map supervisor to a configured, nonempty workspace group.");
            }
        }
        if (path.endsWith("/overdue") && java.util.stream.StreamSupport.stream(template.path("recipients").spliterator(),false)
                .noneMatch(v -> Set.of("supervisor","operators").contains(v.asString())))
            issues.blocker("WF_MAIL_ESCALATION_REQUIRED", path, id, null, "Overdue email needs supervisor or operator recipients.");
    }
    private static boolean cycle(String id, Map<String,List<String>> edges, Set<String> visiting, Set<String> done) {
        if (done.contains(id)) return false;
        if (!visiting.add(id)) return true;
        for (String next : edges.getOrDefault(id, List.of())) if (cycle(next, edges, visiting, done)) return true;
        visiting.remove(id); done.add(id); return false;
    }
    private static int maxCalls(String id, Map<String,JsonNode> nodes, Map<String,List<String>> edges, Map<String,Integer> memo) {
        if (memo.containsKey(id)) return memo.get(id);
        int rest = 0;
        for (String next : edges.getOrDefault(id,List.of())) rest = Math.max(rest,maxCalls(next,nodes,edges,memo));
        int total = rest + ("http".equals(nodes.get(id).path("type").asString()) ? 1 : 0);
        memo.put(id,total); return total;
    }
}
