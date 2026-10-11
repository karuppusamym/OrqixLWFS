package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Enums.Route;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition.Transition;
import com.orqix.compiler.internal.rules.FieldTypes.Resolution;
import com.orqix.compiler.internal.rules.FieldTypes.Resolved;
import com.orqix.compiler.internal.rules.FieldTypes.Unknown;

/**
 * "Wait for a message" steps (S16): the field that identifies the request must exist, hold a text or whole number and
 * always have a value; payload fields are declared once; two waits that could be open at the same time (different
 * branches of one "Run in parallel") must not listen for the same message about the same field. The time limit and its
 * {@code timedOut} route are checked with the other timers ({@link TimerRules}) and routes ({@link GraphRules}).
 */
final class MessageRules {

    private MessageRules() {
    }

    static void check(RuleContext rc) {
        List<Node.WaitForMessage> waits = new java.util.ArrayList<>();
        for (Node n : rc.definition().nodes()) {
            if (rc.graph().isShadowed(n) || !(n instanceof Node.WaitForMessage m)) {
                continue;
            }
            waits.add(m);
            correlation(rc, m);
            payload(rc, m);
        }
        duplicates(rc, waits);
    }

    private static void correlation(RuleContext rc, Node.WaitForMessage m) {
        String path = m.configPath("correlationField");
        Resolution resolution = rc.types().resolve(m.correlationField());
        if (resolution instanceof Unknown u) {
            rc.issues().blocker("WF_MESSAGE_CORRELATION_UNKNOWN", path, m.id(), null, q(m)
                    + " identifies the request by " + m.correlationField() + ", but " + lower(u.message()));
            return;
        }
        if (!(resolution instanceof Resolved r)) {
            return;
        }
        FieldKind kind = r.type().kind();
        if (kind != FieldKind.STRING && kind != FieldKind.INTEGER) {
            rc.issues().blocker("WF_MESSAGE_CORRELATION_TYPE", path, m.id(), null, q(m) + " identifies the request by "
                    + m.correlationField() + ", but that is " + kind.description() + ". Choose a text or a whole"
                    + " number such as an order number or a reference.");
            return;
        }
        if (kind == FieldKind.STRING && r.producer() == null) {
            var field = rc.definition().inputSchema().properties().get(r.key());
            boolean finite = field.enumValues() != null && !field.enumValues().isEmpty()
                    && field.enumValues().stream().allMatch(v -> !v.isEmpty() && v.length() <= 200);
            if (!finite && (field.minLength() == null || field.minLength() < 1 || field.maxLength() == null || field.maxLength() > 200))
                rc.issues().blocker("WF_MESSAGE_CORRELATION_BOUNDS", path, m.id(), null,
                        q(m) + " needs a correlation text field bounded to 1..200 characters. Set its minimum and maximum length.");
        }
        Conditions.availability(rc, m.correlationField(), m, path, null);
        if (r.producer() == null && !rc.definition().inputSchema().required().contains(r.key())) {
            rc.issues().blocker("WF_MESSAGE_CORRELATION_OPTIONAL", path, m.id(), null, q(m) + " identifies the request"
                    + " by " + q(rc.definition().inputSchema().properties().get(r.key()).title()) + ", but that field"
                    + " is optional on the request form, so there may be nothing to match the message with. Make the"
                    + " field required.");
        }
    }

    private static void payload(RuleContext rc, Node.WaitForMessage m) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < m.payload().size(); i++) {
            Node.PayloadField f = m.payload().get(i);
            if (!seen.add(f.name())) {
                rc.issues().blocker("WF_MESSAGE_PAYLOAD_DUPLICATE", m.configPath("payload") + "/" + i + "/name",
                        m.id(), null, q(m) + " declares the message value " + q(f.name())
                                + " more than once. Give each value its own name.");
            }
        }
    }

    /** Two waits for the same message about the same field that can be open together would compete for one message. */
    private static void duplicates(RuleContext rc, List<Node.WaitForMessage> waits) {
        for (int a = 0; a < waits.size(); a++) {
            for (int b = a + 1; b < waits.size(); b++) {
                Node.WaitForMessage first = waits.get(a);
                Node.WaitForMessage second = waits.get(b);
                if (first.message().equals(second.message())
                        && first.correlationField().equals(second.correlationField())
                        && concurrent(rc, first, second)) {
                    rc.issues().blocker("WF_MESSAGE_DUPLICATE_WAIT", second.configPath("message"), second.id(), null,
                            q(second) + " and " + q(first) + " both wait for the message " + q(second.message())
                                    + " about " + second.correlationField() + " in different parallel paths, so"
                                    + " they could take the same message. Use a different message name for one of"
                                    + " them, or wait for it before running in parallel.");
                }
            }
        }
    }

    /** True when both steps lie in different paths of one "Run in parallel" step. */
    private static boolean concurrent(RuleContext rc, Node a, Node b) {
        Graph g = rc.graph();
        for (Node n : rc.definition().nodes()) {
            if (g.isShadowed(n) || !(n instanceof Node.ParallelSplit split)) {
                continue;
            }
            Node join = g.node(split.joinRef());
            BitSet inFirst = null;
            BitSet inSecond = null;
            for (Transition t : g.outgoing(split)) {
                BitSet region = reachBefore(g, g.node(t.to()), join);
                if (region.get(a.index())) {
                    inFirst = region;
                }
                if (region.get(b.index())) {
                    inSecond = region;
                }
            }
            if (inFirst != null && inSecond != null && inFirst != inSecond) {
                return true;
            }
        }
        return false;
    }

    private static BitSet reachBefore(Graph g, Node head, Node stop) {
        BitSet seen = new BitSet();
        Deque<Node> work = new ArrayDeque<>();
        if (head != null && head != stop) {
            seen.set(head.index());
            work.add(head);
        }
        while (!work.isEmpty()) {
            Node n = work.poll();
            for (Transition t : g.outgoing(n)) {
                Node to = g.node(t.to());
                if (to != stop && !seen.get(to.index())) {
                    seen.set(to.index());
                    work.add(to);
                }
            }
        }
        return seen;
    }

    /**
     * With a time limit the payload exists only when the message arrived. True when {@code target} can also be reached
     * from the start without taking the step's {@code next} path (for example along its {@code timedOut} path).
     */
    static boolean reachableWithoutMessage(RuleContext rc, Node.WaitForMessage m, Node target) {
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
                if (n == m && t.on() == Route.NEXT) {
                    continue;
                }
                Node to = g.node(t.to());
                if (!seen.get(to.index())) {
                    seen.set(to.index());
                    work.add(to);
                }
            }
        }
        return false;
    }

    private static String lower(String message) {
        return message.isEmpty() ? message : Character.toLowerCase(message.charAt(0)) + message.substring(1);
    }
}
