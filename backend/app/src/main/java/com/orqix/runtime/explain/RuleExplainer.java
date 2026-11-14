package com.orqix.runtime.explain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.orqix.runtime.api.ExplainDtos;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.runtime.api.WaitDtos;

/**
 * Rule-based explanation (CG-A07): the answer when AI is off, unavailable, over budget or outside the residency rule,
 * and the baseline the AI answer is checked against. Works only from {@link RunFacts}: every evidence id is a fact id
 * and every suggested action is one of the caller's permitted actions. Pure Java.
 */
public final class RuleExplainer {

    /** The explanation without its facts. */
    public record Explanation(String summary, String cause, List<String> evidence,
            List<ExplainDtos.SuggestedAction> suggestedActions) {
    }

    private final RunFacts.Input in;
    private final RunFacts.Result facts;
    private final Set<String> evidence = new LinkedHashSet<>();
    private final List<ExplainDtos.SuggestedAction> suggested = new ArrayList<>();

    private RuleExplainer(RunFacts.Input in, RunFacts.Result facts) {
        this.in = in;
        this.facts = facts;
    }

    public static Explanation explain(RunFacts.Input in, RunFacts.Result facts) {
        return new RuleExplainer(in, facts).run();
    }

    private Explanation run() {
        RuntimeDtos.RunDetail run = in.run();
        cite("run");
        if ("completed".equals(run.lifecycle()) || "cancelled".equals(run.lifecycle())) {
            return done("This request has " + RunFacts.lifecycleText(run.lifecycle()) + ". Nothing is stuck.",
                    "The request ended" + (run.outcome() == null ? "." : " with the outcome \"" + run.outcome() + "\"."));
        }
        if (run.suspension() != null) {
            cite("suspension");
            suggest("resumeRun", "The request continues only after it is resumed.");
            return done("This request is suspended and waits for an operator to resume it.",
                    "An operator suspended it on " + RunFacts.time(run.suspension().since())
                            + "; approvals, timers and messages are held while it is suspended.");
        }
        String others = otherWaits(run.attention() == null ? null : run.attention().nodeId());
        if (run.attention() != null) {
            return attention(run, others);
        }
        return waiting(run);
    }

    // ------------------------------------------------------------------ needs attention

    private Explanation attention(RuntimeDtos.RunDetail run, String others) {
        RuntimeDtos.Attention a = run.attention();
        cite("attention");
        cite("failingSince:" + a.nodeId());
        cite("model:" + a.nodeId());
        RuntimeDtos.Effect effect = run.effects() == null ? null : run.effects().stream()
                .filter(e -> Objects.equals(e.nodeId(), a.nodeId()) && !"succeeded".equals(e.state())).findFirst()
                .orElse(null);
        if (effect != null) {
            cite("effect:" + effect.id());
        }
        String step = stepLabel(a.nodeId());
        String since = facts.ref("failingSince:" + a.nodeId()) == null ? RunFacts.time(a.since())
                : RunFacts.time(firstFailureAt(a.nodeId()));
        String connection = effect != null ? effect.connectionKey() : facts.problemConnections().stream().findFirst()
                .orElse(null);
        if (connection != null) {
            cite("connection:" + connection);
            cite("token:" + connection);
            cite("circuit:" + connection);
            cite("lastError:" + connection);
        }
        boolean credential = effect != null && RunFacts.isCredentialProblem(effect.lastErrorCode(), effect.lastHttpStatus())
                || connection != null && facts.ref("token:" + connection) != null;
        String cause;
        String summary;
        if (connection != null && facts.problemConnections().contains(connection) && credential) {
            String answer = effect != null && effect.lastHttpStatus() != null ? "HTTP " + effect.lastHttpStatus()
                    : "a credential error";
            summary = "Step " + step + " is stuck: " + connection + " has refused the call with " + answer + " since "
                    + since + ".";
            cause = "The connection's credential was refused or could not be obtained (for example the token was"
                    + " rotated, expired or lost a permission). Retrying does not help until the binding's credential"
                    + " is fixed or authorized again.";
            suggestConnection(connection, "The credential of " + connection + " must be fixed first.");
            suggest("retryRun", "Run the step again once the credential works.");
        } else if (connection != null && facts.problemConnections().contains(connection)) {
            summary = "Step " + step + " is stuck: the binding of " + connection + " cannot be used.";
            cause = "The connection has no usable binding in this workspace (missing, switched off or its host is not"
                    + " allowed), so the call was not sent.";
            suggestConnection(connection, "The binding of " + connection + " must be usable first.");
            suggest("retryRun", "Run the step again once the binding works.");
        } else if ("EFFECT_UNKNOWN".equals(a.reason())) {
            summary = "Step " + step + " is stuck: nobody knows whether its call to " + (connection == null
                    ? "the other system" : connection) + " took effect.";
            cause = "The call may have been applied before the answer was lost. Orqix does not repeat it on its own;"
                    + " the other system must be checked first.";
            if (effect != null) {
                suggest("reconcileEffect:" + effect.id(), "Record with evidence what happened in the other system.");
            }
        } else if ("RETRIES_EXHAUSTED".equals(a.reason())) {
            summary = "Step " + step + " is stuck: " + (connection == null ? "the other system" : connection)
                    + " could not be reached after all attempts (failing since " + since + ").";
            cause = facts.ref("circuit:" + connection) != null
                    ? "Calls to " + connection + " keep failing and are paused by the circuit breaker."
                    : "The other system failed or did not answer"
                            + (effect != null && effect.lastHttpStatus() != null ? " (last HTTP " + effect.lastHttpStatus() + ")" : "")
                            + " on every attempt.";
            suggest("retryRun", "Run the step again once the other system is available.");
        } else if ("EFFECT_REJECTED".equals(a.reason())) {
            summary = "Step " + step + " is stuck: " + (connection == null ? "the other system" : connection)
                    + " rejected the call" + (effect != null && effect.lastHttpStatus() != null
                    ? " with HTTP " + effect.lastHttpStatus() : "") + " since " + since + ".";
            cause = "A definitive answer means that repeating the same call fails the same way: the request data or the"
                    + " connection's configuration must change first.";
            suggest("correctRunInput", "Correct the input if it caused the rejection.");
            suggest("retryRun", "Run the step again once the cause is fixed.");
        } else if ("CONNECTION_UNAVAILABLE".equals(a.reason())) {
            summary = "Step " + step + " is stuck: its connection could not be used.";
            cause = "The connection's binding or credential was not available when the step ran.";
            suggest("retryRun", "Run the step again once the connection is available.");
        } else if ("RUN_WAITING_ON_NOTHING".equals(a.reason())) {
            summary = "This request waits on nothing: no step can move it forward.";
            cause = "The request and the workflow engine disagree about what is open.";
            suggest("repairRun", "Compare with the engine and rebuild missing steps.");
        } else {
            summary = "Step " + step + " stopped with " + RunFacts.code(a.reason()) + " on " + RunFacts.time(a.since())
                    + ".";
            cause = "The step failed and the request needs an operator.";
            suggest("retryRun", "Run the step again once the cause is fixed.");
        }
        for (var e : run.effects() == null ? List.<RuntimeDtos.Effect>of() : run.effects()) {
            if ("unknown".equals(e.state())) {
                suggest("reconcileEffect:" + e.id(), "An earlier call has an unknown outcome; record it first.");
            }
        }
        if (others != null) {
            summary = summary + " " + others;
        }
        suggest("cancelRun", "Only if the request is no longer needed.");
        return done(summary, cause);
    }

    // ------------------------------------------------------------------ running or waiting

    private Explanation waiting(RuntimeDtos.RunDetail run) {
        WaitDtos.RunWaitList waits = in.waits();
        List<WaitDtos.RunWait> items = waits == null ? List.of() : waits.items();
        for (String key : in.connections().keySet()) {
            if (facts.ref("circuit:" + key) != null) {
                cite("circuit:" + key);
                cite("deferred:" + key);
                return done("This request waits because calls to " + key + " are paused.",
                        "Repeated failures opened the circuit breaker of " + key + ". Calls wait without using their"
                                + " attempts and resume on their own after a trial call; nothing needs to be done unless"
                                + " " + key + " stays down.");
            }
        }
        for (WaitDtos.RunWait w : items) {
            WaitDtos.PollCheck c = w.check();
            if (c != null && c.consecutiveErrors() > 0) {
                cite("wait:" + w.nodeId());
                cite("check:" + c.waitId());
                if (w.connectionKey() != null) {
                    cite("connection:" + w.connectionKey());
                    if (facts.problemConnections().contains(w.connectionKey())) {
                        suggestConnection(w.connectionKey(), "The checks are refused by " + w.connectionKey() + ".");
                    }
                }
                suggest("checkWaitNow:" + c.waitId(), "Check once more after the cause is fixed.");
                suggest("extendWaitDeadline:" + c.waitId(), "Give the check more time if the system is slow.");
                return done("The check at step " + label(w) + " keeps failing (" + c.consecutiveErrors()
                        + " error(s) in a row).", "The repeated check could not get an answer from "
                        + (w.connectionKey() == null ? "the other system" : w.connectionKey()) + "; it continues until"
                        + " its deadline" + (c.deadlineAt() == null ? "." : " " + RunFacts.time(c.deadlineAt()) + "."));
            }
        }
        for (RuntimeDtos.TaskSummary t : run.tasks() == null ? List.<RuntimeDtos.TaskSummary>of() : run.tasks()) {
            if ("open".equals(t.status()) && t.dueAt() != null && t.dueAt().isBefore(in.now())) {
                cite("task:" + t.id());
                cite("wait:" + t.nodeId());
                suggest("reassignTask:" + t.id(), "Give the overdue task to someone who can act now.");
                return done("This request waits for task " + quote(t.label(), t.nodeId()) + ", which is overdue.",
                        "The task is " + (t.assignee() == null ? "not claimed by anyone" : "held by a person who has"
                                + " not acted") + " and was due " + RunFacts.time(t.dueAt()) + ".");
            }
        }
        if (!items.isEmpty()) {
            WaitDtos.RunWait w = items.get(0);
            cite("wait:" + w.nodeId());
            cite("model:" + w.nodeId());
            String what = switch (w.kind() == null ? "" : w.kind()) {
                case "timer" -> "a timer that ends " + RunFacts.time(w.dueAt() != null ? w.dueAt() : w.deadlineAt())
                        + "; it continues on its own";
                case "approval" -> "a person to act on step " + label(w);
                case "message", "signal" -> "a " + w.kind() + " from outside the request";
                case "callback" -> "a callback from another system";
                case "externalJob" -> "an external worker to finish its job";
                case "childRequest" -> "the request it started";
                case "poll" -> "a repeated check to see the expected result";
                default -> "step " + label(w);
            };
            for (RuntimeDtos.TaskSummary t : run.tasks() == null ? List.<RuntimeDtos.TaskSummary>of() : run.tasks()) {
                if ("open".equals(t.status()) && Objects.equals(t.nodeId(), w.nodeId())) {
                    cite("task:" + t.id());
                    suggest("reassignTask:" + t.id(), "Only if the task's holder cannot act.");
                }
            }
            return done("This request is not stuck: it waits for " + what + ".",
                    "Waiting since " + RunFacts.time(w.since()) + " is expected at this step"
                            + (items.size() > 1 ? "; " + (items.size() - 1) + " other wait(s) are open." : "."));
        }
        for (var step : run.currentSteps() == null ? List.<RuntimeDtos.CurrentStep>of() : run.currentSteps()) {
            cite("step:" + step.nodeId());
            cite("model:" + step.nodeId());
        }
        return done("This request is running; nothing is stuck right now.",
                "No step needs attention and nothing waits longer than expected.");
    }

    // ------------------------------------------------------------------ helpers

    private String otherWaits(String stuckNode) {
        if (in.waits() == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (WaitDtos.RunWait w : in.waits().items()) {
            if (Objects.equals(w.nodeId(), stuckNode)) {
                continue;
            }
            cite("wait:" + w.nodeId());
            parts.add("timer".equals(w.kind()) ? "step " + label(w) + " waits for a timer until "
                    + RunFacts.time(w.dueAt() != null ? w.dueAt() : w.deadlineAt())
                    : "step " + label(w) + " waits (" + w.kind() + ")");
        }
        return parts.isEmpty() ? null : "Meanwhile " + String.join("; ", parts.subList(0, Math.min(3, parts.size())))
                + ", which is expected.";
    }

    private java.time.Instant firstFailureAt(String nodeId) {
        String id = facts.ref("failingSince:" + nodeId);
        return facts.facts().stream().filter(f -> f.id().equals(id)).map(ExplainDtos.Fact::at).findFirst()
                .orElse(null);
    }

    private void suggestConnection(String key, String why) {
        suggest("updateConnectionBinding:" + key, why);
        suggest("askAdministrator:connection:" + key, why);
    }

    private void cite(String ref) {
        String id = facts.ref(ref);
        if (id != null) {
            evidence.add(id);
        }
    }

    /** Only permitted actions are ever suggested; an action the caller does not have is skipped. */
    private void suggest(String actionId, String why) {
        if (facts.hasAction(actionId) && suggested.stream().noneMatch(s -> s.actionId().equals(actionId))) {
            suggested.add(new ExplainDtos.SuggestedAction(actionId, why));
        }
    }

    private Explanation done(String summary, String cause) {
        return new Explanation(summary, cause, List.copyOf(evidence), List.copyOf(suggested));
    }

    private String stepLabel(String nodeId) {
        for (var node : in.definition().path("nodes")) {
            if (nodeId != null && nodeId.equals(node.path("id").asString(null))) {
                return quote(node.path("label").asString(null), nodeId);
            }
        }
        return nodeId == null ? "(unknown)" : nodeId;
    }

    private String label(WaitDtos.RunWait w) {
        return w.label() != null ? quote(w.label(), w.nodeId()) : stepLabel(w.nodeId());
    }

    private static String quote(String label, String fallback) {
        return label == null ? String.valueOf(fallback) : "\"" + label + "\"";
    }
}
