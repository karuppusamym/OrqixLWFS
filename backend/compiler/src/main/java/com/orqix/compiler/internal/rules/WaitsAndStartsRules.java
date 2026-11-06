package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.orqix.compiler.AdapterMessages;
import com.orqix.compiler.CompileContext;
import com.orqix.compiler.internal.json.StrictJson;
import com.orqix.compiler.internal.model.Durations;
import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Enums.Route;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WaitExtras;
import com.orqix.compiler.internal.model.WorkflowDefinition;
import com.orqix.compiler.internal.model.WorkflowDefinition.Transition;
import com.orqix.compiler.internal.rules.FieldTypes.Resolution;
import com.orqix.compiler.internal.rules.FieldTypes.Resolved;
import com.orqix.compiler.internal.rules.FieldTypes.Unknown;

import tools.jackson.databind.JsonNode;

/**
 * Rules of docs/build/S139: shared working calendars (CG-W05), reminders while waiting (CG-W06), "Wait for the first"
 * (CG-W07) and message handlers (CG-E08).
 * <ul>
 * <li>{@code WF_CALENDAR_NOT_PINNED}: every calendar a step counts working time in is pinned in
 * {@code dependencies.assets} (kind calendar); the server checks that the pinned version exists and is not archived.</li>
 * <li>{@code WF_REMIND_*}: where reminders are allowed, their interval, adapter, recipients, texts and values.</li>
 * <li>{@code WF_FIRST_*}: branches, their settings and exactly one path per branch.</li>
 * <li>{@code WF_HANDLER_*}: the message, the field that identifies the request and the message's values.</li>
 * </ul>
 */
final class WaitsAndStartsRules {

    /** Reminder interval bounds: one hour to 30 days. */
    static final long REMIND_MIN_MINUTES = 60;
    static final long REMIND_MAX_MINUTES = 30L * 24 * 60;
    /** Placeholders every reminder text may use, next to the names of its inputMapping. */
    static final List<String> REMIND_PLACEHOLDERS = List.of("reference", "workflow", "step", "since", "count",
            "maxTimes");

    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9._%+-]{1,64}@([A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63})+)$");

    private WaitsAndStartsRules() {
    }

    static void check(RuleContext rc) {
        calendars(rc);
        reminders(rc);
        for (Node n : rc.definition().nodes()) {
            if (n instanceof Node.WaitForFirst w && !rc.graph().isShadowed(n)) {
                first(rc, w);
            }
        }
        handlers(rc);
    }

    // ------------------------------------------------------------------ CG-W05 calendars

    private static void calendars(RuleContext rc) {
        WorkflowDefinition d = rc.definition();
        WaitExtras w = d.waits();
        Map<String, String> refs = new LinkedHashMap<>(); // path -> key
        Map<String, String> owners = new HashMap<>();
        for (Node n : d.nodes()) {
            WaitExtras.Working working = w.working(n.id());
            if (working != null && working.calendarRef() != null) {
                refs.put(n.configPath("calendarRef"), working.calendarRef());
                owners.put(n.configPath("calendarRef"), n.id());
            }
            String timeout = w.timeoutCalendar(n.id());
            if (timeout != null) {
                String path = n instanceof Node.Http ? n.configPath("poll") + "/timeoutCalendarRef"
                        : n.configPath("timeoutCalendarRef");
                refs.put(path, timeout);
                owners.put(path, n.id());
            }
            if (n instanceof Node.WaitForFirst first) {
                for (Node.FirstBranch b : first.branches()) {
                    if (b.calendarRef() != null) {
                        refs.put(b.path(first) + "/calendarRef", b.calendarRef());
                        owners.put(b.path(first) + "/calendarRef", n.id());
                    }
                }
            }
        }
        refs.forEach((path, key) -> {
            if (!w.calendarPins().containsKey(key)) {
                rc.issues().blocker("WF_CALENDAR_NOT_PINNED", path, owners.get(path), null, "The working calendar "
                        + q(key) + " is not pinned. Choose the calendar again so the workflow keeps one version of it"
                        + " (dependencies.assets, kind calendar).");
            }
        });
        Set<String> used = new HashSet<>(refs.values());
        w.calendarPins().values().forEach(pin -> {
            if (!used.contains(pin.key())) {
                rc.issues().warning("WF_CALENDAR_UNUSED", pin.path(), null, null, "The working calendar "
                        + q(pin.key()) + " is pinned but no step counts working time in it. Remove the link.");
            }
        });
    }

    // ------------------------------------------------------------------ CG-W06 reminders

    private static void reminders(RuleContext rc) {
        rc.definition().waits().reminders().forEach((stepId, r) -> {
            Node n = rc.graph().node(stepId);
            if (n == null || rc.graph().isShadowed(n)) {
                return;
            }
            boolean allowed = n instanceof Node.WaitForMessage || n instanceof Node.ExternalTask
                    || n instanceof Node.Http h && h.polls();
            if (!allowed) {
                rc.issues().blocker("WF_REMIND_STEP", r.path(), n.id(), null, q(n) + " does not wait for anyone"
                        + " outside, so it cannot send reminders. Reminders are for message waits, callbacks, checks"
                        + " and outside workers.");
                return;
            }
            remind(rc, n, r);
        });
    }

    private static void remind(RuleContext rc, Node n, WaitExtras.Remind r) {
        long every = Durations.minutes(r.every());
        if (every < REMIND_MIN_MINUTES || every > REMIND_MAX_MINUTES) {
            rc.issues().blocker("WF_REMIND_INTERVAL", r.path() + "/every", n.id(), null, q(n) + " reminds every "
                    + (every < 0 ? r.every() : Durations.describe(every)) + ". Choose from 1 hour (PT1H) to 30 days"
                    + " (P30D).");
        }
        CompileContext.AdapterInfo adapter = rc.workspace().adapters().get(r.adapterRef());
        String kind = adapter == null ? null : adapter.kind();
        if (adapter == null) {
            rc.issues().blocker("WF_REMIND_ADAPTER", r.path() + "/adapter", n.id(), null, q(n) + " reminds through the"
                    + " adapter " + q(r.adapterRef()) + ", which does not exist in this workspace.");
        } else if (!"email".equals(kind) && !"teams".equals(kind)) {
            rc.issues().blocker("WF_REMIND_ADAPTER", r.path() + "/adapter", n.id(), null, "Reminders are sent by email"
                    + " or Teams; " + q(adapter.name()) + " is a " + kind + " adapter.");
            return;
        } else if (!adapter.enabled()) {
            rc.issues().blocker("WF_REMIND_ADAPTER", r.path() + "/adapter", n.id(), null, q(n) + " reminds through "
                    + q(adapter.name()) + ", which is disabled.");
        }
        JsonNode config = adapter == null ? null : StrictJson.parse(adapter.configJson());
        Set<String> names = new LinkedHashSet<>(REMIND_PLACEHOLDERS);
        names.addAll(r.inputMapping().keySet());
        if ("teams".equals(kind)) {
            if (!r.to().isEmpty() || r.toField() != null) {
                rc.issues().blocker("WF_REMIND_RECIPIENT", r.path() + "/to", n.id(), null, "A Teams adapter posts to its"
                        + " own channel; remove the recipients.");
            }
            if (r.text() == null || r.subject() != null || r.body() != null) {
                rc.issues().blocker("WF_REMIND_TEMPLATE", r.path() + "/template", n.id(), null, "A Teams reminder"
                        + " needs a text (template.text) and no subject or body.");
            }
            placeholders(rc, n, r, "text", r.text(), names);
        } else {
            recipients(rc, n, r, config);
            if (r.subject() == null || r.body() == null || r.text() != null) {
                rc.issues().blocker("WF_REMIND_TEMPLATE", r.path() + "/template", n.id(), null, "An email reminder needs"
                        + " a subject and a body (template.subject, template.body) and no text.");
            }
            if (r.subject() != null && (r.subject().indexOf('\n') >= 0 || r.subject().indexOf('\r') >= 0)) {
                rc.issues().blocker("WF_REMIND_TEMPLATE", r.path() + "/template/subject", n.id(), null,
                        "Line breaks are not allowed in the subject.");
            }
            placeholders(rc, n, r, "subject", r.subject(), names);
            placeholders(rc, n, r, "body", r.body(), names);
        }
        r.inputMapping().forEach((name, ref) -> {
            String path = r.path() + "/inputMapping/" + name;
            if (REMIND_PLACEHOLDERS.contains(name)) {
                rc.issues().blocker("WF_REMIND_INPUT", path, n.id(), null, q(name) + " is a fixed placeholder; choose"
                        + " another name.");
                return;
            }
            if (!ref.startsWith("/input/") || ref.indexOf('/', "/input/".length()) >= 0) {
                rc.issues().blocker("WF_REMIND_INPUT", path, n.id(), null, "A reminder uses request fields only (it is"
                        + " sent while the step waits); choose a field of the request form.");
                return;
            }
            Resolution res = rc.types().resolve(ref);
            if (res instanceof Unknown u) {
                rc.issues().blocker("WF_REMIND_INPUT", path, n.id(), null, u.message());
            } else if (res instanceof Resolved rr && (rr.type().kind() == FieldKind.ARRAY
                    || rr.type().kind() == FieldKind.OBJECT || rr.type().kind() == FieldKind.ATTACHMENT)) {
                rc.issues().blocker("WF_REMIND_INPUT", path, n.id(), null, "A reminder shows single values only.");
            }
        });
    }

    private static void recipients(RuleContext rc, Node n, WaitExtras.Remind r, JsonNode config) {
        boolean literal = !r.to().isEmpty();
        boolean field = r.toField() != null;
        if (literal == field) {
            rc.issues().blocker("WF_REMIND_RECIPIENT", r.path() + "/to", n.id(), null, q(n) + ": an email reminder"
                    + " needs either fixed recipients (to) or one request field with the address (toField).");
            return;
        }
        Set<String> allowed = new LinkedHashSet<>();
        if (config != null) {
            config.path("allowedRecipientDomains").forEach(v -> allowed.add(v.asString().toLowerCase(Locale.ROOT)));
        }
        if (literal) {
            for (int i = 0; i < r.to().size(); i++) {
                String address = r.to().get(i);
                Matcher m = EMAIL.matcher(address);
                String path = r.path() + "/to/" + i;
                if (!m.matches()) {
                    rc.issues().blocker("WF_REMIND_RECIPIENT", path, n.id(), null, q(address)
                            + " is not a plain email address.");
                } else if (config != null && !allowed.contains(m.group(1).toLowerCase(Locale.ROOT))) {
                    rc.issues().blocker("WF_REMIND_RECIPIENT", path, n.id(), null, "The domain "
                            + q(m.group(1).toLowerCase(Locale.ROOT)) + " is not approved by this adapter. Approved"
                            + " domains: " + String.join(", ", allowed) + ".");
                }
            }
            return;
        }
        // D-CG7: an address from the request, accepted only on the adapter's approved domains (checked again per send).
        String path = r.path() + "/toField";
        if (!r.toField().startsWith("/input/") || r.toField().indexOf('/', "/input/".length()) >= 0) {
            rc.issues().blocker("WF_REMIND_RECIPIENT_FIELD", path, n.id(), null, "The address must come from a field"
                    + " of the request form.");
            return;
        }
        Resolution res = rc.types().resolve(r.toField());
        if (res instanceof Unknown u) {
            rc.issues().blocker("WF_REMIND_RECIPIENT_FIELD", path, n.id(), null, u.message());
            return;
        }
        if (res instanceof Resolved rr && rr.type().kind() != FieldKind.STRING) {
            rc.issues().blocker("WF_REMIND_RECIPIENT_FIELD", path, n.id(), null, "The address field must be text, not "
                    + rr.type().kind().description() + ".");
        }
        if (config != null && allowed.isEmpty()) {
            rc.issues().blocker("WF_REMIND_RECIPIENT_DOMAINS", path, n.id(), null, "The adapter approves no recipient"
                    + " domains, so an address from the request could never be used. An administrator must approve"
                    + " the domains first.");
        }
    }

    private static void placeholders(RuleContext rc, Node n, WaitExtras.Remind r, String member, String text,
            Set<String> names) {
        if (text == null) {
            return;
        }
        String problem = AdapterMessages.problem(text, names);
        if (problem != null) {
            rc.issues().blocker("WF_REMIND_PLACEHOLDER", r.path() + "/template/" + member, n.id(), null, problem);
        }
    }

    // ------------------------------------------------------------------ CG-W07 wait for the first

    private static void first(RuleContext rc, Node.WaitForFirst w) {
        Set<String> ids = new HashSet<>();
        Set<String> fields = new HashSet<>();
        Set<String> messages = new HashSet<>();
        int timers = 0;
        for (Node.FirstBranch b : w.branches()) {
            String path = b.path(w);
            if (!ids.add(b.id())) {
                rc.issues().blocker("WF_FIRST_BRANCH_DUPLICATE", path + "/id", w.id(), null, q(w) + " has two branches"
                        + " with the ID " + q(b.id()) + ".");
            }
            settings(rc, w, b, path);
            for (int i = 0; i < b.payload().size(); i++) {
                String name = b.payload().get(i).name();
                if (Node.WaitForFirst.BRANCH_OUTPUT.equals(name) || !fields.add(name)) {
                    rc.issues().blocker("WF_FIRST_FIELD_DUPLICATE", path + "/payload/" + i + "/name", w.id(), null,
                            "The value " + q(name) + " is declared " + (Node.WaitForFirst.BRANCH_OUTPUT.equals(name)
                                    ? "by the step itself (the winning branch)" : "by more than one branch")
                            + ". Give each value its own name.");
                }
            }
            if ("message".equals(b.kind()) && b.message() != null && !messages.add(b.message())) {
                rc.issues().blocker("WF_FIRST_MESSAGE_DUPLICATE", path + "/message", w.id(), null, "Two branches of "
                        + q(w) + " wait for the message " + q(b.message()) + "; one message can only win once.");
            }
            if ("timer".equals(b.kind())) {
                timers++;
            }
        }
        if (timers > 1) {
            rc.issues().warning("WF_FIRST_TIMERS", w.configPath("branches"), w.id(), null, q(w) + " has " + timers
                    + " time branches; only the earliest can ever win.");
        }
        routes(rc, w);
    }

    private static void settings(RuleContext rc, Node.WaitForFirst w, Node.FirstBranch b, String path) {
        Map<String, Object> present = new LinkedHashMap<>();
        present.put("message", b.message());
        present.put("correlationField", b.correlationField());
        present.put("payload", b.payload().isEmpty() ? null : b.payload());
        present.put("signal", b.signal());
        present.put("tokenValidFor", b.tokenValidFor());
        present.put("after", b.after());
        present.put("untilField", b.untilField());
        present.put("atTime", b.atTime());
        present.put("workingDuration", b.workingDuration());
        present.put("calendarRef", b.calendarRef());
        present.put("check", b.check());
        Set<String> allowed = switch (b.kind()) {
            case "message" -> Set.of("message", "correlationField", "payload");
            case "signal" -> Set.of("signal");
            case "callback" -> Set.of("tokenValidFor", "payload");
            case "timer" -> Set.of("after", "untilField", "atTime", "workingDuration", "calendarRef");
            default -> Set.of("check", "payload");
        };
        present.forEach((member, value) -> {
            if (value != null && !allowed.contains(member)) {
                rc.issues().blocker("WF_FIRST_BRANCH_SETTING", path + "/" + member, w.id(), null, "The " + b.kind()
                        + " branch " + q(b.label()) + " does not use " + q(member) + "; remove it.");
            }
        });
        switch (b.kind()) {
            case "message" -> {
                if (b.message() == null || b.correlationField() == null) {
                    missing(rc, w, b, path, "a message name and the field that identifies the request");
                    return;
                }
                correlation(rc, w, b.correlationField(), path + "/correlationField");
            }
            case "signal" -> {
                if (b.signal() == null) {
                    missing(rc, w, b, path, "the signal");
                }
            }
            case "callback" -> {
                if (b.tokenValidFor() == null) {
                    missing(rc, w, b, path, "how long a callback token is valid (tokenValidFor)");
                    return;
                }
                long minutes = Durations.minutes(b.tokenValidFor());
                if (minutes < Durations.MIN_MINUTES || minutes > MessageRules.CALLBACK_MAX_MINUTES) {
                    rc.issues().blocker("WF_CALLBACK_TOKEN_VALIDITY", path + "/tokenValidFor", w.id(), null,
                            "Choose a token validity from 1 minute (PT1M) to 30 days (P30D).");
                }
            }
            case "timer" -> timer(rc, w, b, path);
            default -> {
                if (b.check() == null) {
                    missing(rc, w, b, path, "the check to run (connection, read-only operation and done condition)");
                }
            }
        }
    }

    private static void timer(RuleContext rc, Node.WaitForFirst w, Node.FirstBranch b, String path) {
        int choices = (b.after() == null ? 0 : 1) + (b.untilField() == null ? 0 : 1)
                + (b.workingDuration() == null ? 0 : 1);
        if (choices != 1) {
            rc.issues().blocker("WF_FIRST_TIMER_CHOICE", path, w.id(), null, "The time branch " + q(b.label())
                    + " needs exactly one of: a length of time (after), a date field (untilField) or working time"
                    + " (workingDuration with a calendar).");
            return;
        }
        if (b.atTime() != null && b.untilField() == null) {
            rc.issues().blocker("WF_FIRST_BRANCH_SETTING", path + "/atTime", w.id(), null, "A time of day belongs to a"
                    + " date field.");
        }
        if ((b.workingDuration() == null) != (b.calendarRef() == null)) {
            rc.issues().blocker("WF_FIRST_BRANCH_SETTING", path + "/calendarRef", w.id(), null, "Working time needs a"
                    + " working calendar, and a calendar needs a working time.");
        }
        if (b.after() != null) {
            TimerRules.durationAt(rc, w, path + "/after", b.after(), "waits");
        } else if (b.workingDuration() != null) {
            TimerRules.durationAt(rc, w, path + "/workingDuration", b.workingDuration(), "waits");
        } else {
            Resolution res = rc.types().resolve(b.untilField());
            if (res instanceof Unknown u) {
                rc.issues().blocker("WF_FIELDREF_UNKNOWN", path + "/untilField", w.id(), null, u.message());
            } else if (res instanceof Resolved r) {
                if (r.type().kind() != FieldKind.DATE) {
                    rc.issues().blocker("WF_WAIT_FIELD_TYPE", path + "/untilField", w.id(), null, "The time branch "
                            + q(b.label()) + " waits until " + b.untilField() + ", which is not a date.");
                } else if (r.type().mayBeAbsent()) {
                    rc.issues().blocker("WF_WAIT_FIELD_OPTIONAL", path + "/untilField", w.id(), null, "The date in "
                            + b.untilField() + " may be missing. Make the field required.");
                }
                Conditions.availability(rc, b.untilField(), w, path + "/untilField", null);
            }
        }
    }

    private static void missing(RuleContext rc, Node.WaitForFirst w, Node.FirstBranch b, String path, String what) {
        rc.issues().blocker("WF_FIRST_BRANCH_SETTING", path, w.id(), null, "The " + b.kind() + " branch "
                + q(b.label()) + " of " + q(w) + " needs " + what + ".");
    }

    /** The field that identifies the request for a message: text (bounded) or a whole number that is always there. */
    private static void correlation(RuleContext rc, Node n, String fieldRef, String path) {
        Resolution res = rc.types().resolve(fieldRef);
        if (res instanceof Unknown u) {
            rc.issues().blocker("WF_MESSAGE_CORRELATION_UNKNOWN", path, n.id(), null, u.message());
            return;
        }
        if (!(res instanceof Resolved r)) {
            return;
        }
        FieldKind kind = r.type().kind();
        if (kind != FieldKind.STRING && kind != FieldKind.INTEGER) {
            rc.issues().blocker("WF_MESSAGE_CORRELATION_TYPE", path, n.id(), null, fieldRef + " is "
                    + kind.description() + "; a message is matched by a text or a whole number.");
            return;
        }
        if (r.type().mayBeAbsent()) {
            rc.issues().blocker("WF_MESSAGE_CORRELATION_OPTIONAL", path, n.id(), null, fieldRef + " may be missing,"
                    + " so there may be nothing to match the message with. Make the field required.");
        }
        if (kind == FieldKind.STRING && r.producer() == null) {
            var field = rc.definition().inputSchema().properties().get(r.key());
            boolean finite = field.enumValues() != null && !field.enumValues().isEmpty()
                    && field.enumValues().stream().allMatch(v -> !v.isEmpty() && v.length() <= 200);
            if (!finite && (field.minLength() == null || field.minLength() < 1 || field.maxLength() == null
                    || field.maxLength() > 200)) {
                rc.issues().blocker("WF_MESSAGE_CORRELATION_BOUNDS", path, n.id(), null, "The identifying text field"
                        + " must be 1 to 200 characters long. Set its minimum and maximum length.");
            }
        }
        if (n instanceof Node) {
            Conditions.availability(rc, fieldRef, n, path, null);
        }
    }

    private static void routes(RuleContext rc, Node.WaitForFirst w) {
        Map<String, Integer> counts = new HashMap<>();
        for (Transition t : rc.leaving(w)) {
            if (t.on() != Route.BRANCH) {
                continue; // reported by GraphRules
            }
            if (w.branch(t.branch()) == null) {
                rc.issues().blocker("WF_FIRST_ROUTE_UNKNOWN", t.path() + "/branch", w.id(), t.id(), "This path is for"
                        + " the branch " + q(t.branch()) + ", which " + q(w) + " does not have.");
                continue;
            }
            counts.merge(t.branch(), 1, Integer::sum);
        }
        for (Node.FirstBranch b : w.branches()) {
            int count = counts.getOrDefault(b.id(), 0);
            if (count != 1) {
                rc.issues().blocker(count == 0 ? "WF_FIRST_ROUTE_MISSING" : "WF_FIRST_ROUTE_DUPLICATE", b.path(w),
                        w.id(), null, "The branch " + q(b.label()) + " of " + q(w) + (count == 0
                                ? " has no path. Draw one path for each branch."
                                : " has " + count + " paths. Keep one."));
            }
        }
    }

    /** @return true when {@code target} can be reached without taking the path of {@code branchId} of {@code w} */
    static boolean reachableWithoutBranch(RuleContext rc, Node.WaitForFirst w, String branchId, Node target) {
        Graph g = rc.graph();
        BitSet seen = new BitSet();
        Deque<Node> work = new ArrayDeque<>();
        for (Node s : g.starts()) {
            seen.set(s.index());
            work.add(s);
        }
        while (!work.isEmpty()) {
            Node n = work.poll();
            if (n == target) {
                return true;
            }
            for (Transition t : g.outgoing(n)) {
                if (n == w && t.on() == Route.BRANCH && branchId.equals(t.branch())) {
                    continue;
                }
                Node to = g.node(t.to());
                if (to != null && !seen.get(to.index())) {
                    seen.set(to.index());
                    work.add(to);
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ CG-E08 message handlers

    private static void handlers(RuleContext rc) {
        WorkflowDefinition d = rc.definition();
        Set<String> waited = new HashSet<>();
        for (Node n : d.nodes()) {
            if (n instanceof Node.WaitForMessage m) {
                waited.add(m.message());
            } else if (n instanceof Node.WaitForFirst w) {
                w.branches().stream().filter(b -> b.message() != null).forEach(b -> waited.add(b.message()));
            }
        }
        for (WorkflowDefinition.Subprocess s : d.subprocesses()) {
            if (!s.messageHandler()) {
                continue;
            }
            String base = s.path() + "/trigger";
            if (s.message() == null || s.correlationField() == null) {
                rc.issues().blocker("WF_SUBPROCESS_SETTING", base, null, null, "The handler " + q(s.label())
                        + " starts on a message; name the message and the request field that identifies the request.");
                continue;
            }
            if (waited.contains(s.message())) {
                rc.issues().blocker("WF_HANDLER_MESSAGE_CLASH", base + "/message", null, null, "The message "
                        + q(s.message()) + " starts the handler " + q(s.label()) + " and is also waited for by a step."
                        + " One message cannot do both; use another name.");
            }
            if (!s.correlationField().startsWith("/input/")) {
                rc.issues().blocker("WF_HANDLER_CORRELATION", base + "/correlationField", null, null, "A handler waits"
                        + " from the start of the request, so the identifying value must be a request field.");
            } else {
                correlationInput(rc, s, base + "/correlationField");
            }
            Set<String> names = new HashSet<>();
            for (int i = 0; i < s.payload().size(); i++) {
                if (!names.add(s.payload().get(i).name())) {
                    rc.issues().blocker("WF_MESSAGE_PAYLOAD_DUPLICATE", base + "/payload/" + i + "/name", null, null,
                            "The handler " + q(s.label()) + " declares " + q(s.payload().get(i).name()) + " twice.");
                }
            }
        }
    }

    private static void correlationInput(RuleContext rc, WorkflowDefinition.Subprocess s, String path) {
        Resolution res = rc.types().resolve(s.correlationField());
        if (res instanceof Unknown u) {
            rc.issues().blocker("WF_MESSAGE_CORRELATION_UNKNOWN", path, null, null, u.message());
            return;
        }
        if (!(res instanceof Resolved r)) {
            return;
        }
        FieldKind kind = r.type().kind();
        if (kind != FieldKind.STRING && kind != FieldKind.INTEGER) {
            rc.issues().blocker("WF_MESSAGE_CORRELATION_TYPE", path, null, null, s.correlationField() + " is "
                    + kind.description() + "; a message is matched by a text or a whole number.");
            return;
        }
        if (!rc.definition().inputSchema().required().contains(r.key()) || r.type().mayBeAbsent()) {
            rc.issues().blocker("WF_MESSAGE_CORRELATION_OPTIONAL", path, null, null, "The handler " + q(s.label())
                    + " is identified by an optional field. Make the field required.");
        }
        if (kind == FieldKind.STRING) {
            var field = rc.definition().inputSchema().properties().get(r.key());
            boolean finite = field.enumValues() != null && !field.enumValues().isEmpty();
            if (!finite && (field.minLength() == null || field.minLength() < 1 || field.maxLength() == null
                    || field.maxLength() > 200)) {
                rc.issues().blocker("WF_MESSAGE_CORRELATION_BOUNDS", path, null, null, "The identifying text field must"
                        + " be 1 to 200 characters long. Set its minimum and maximum length.");
            }
        }
    }
}
