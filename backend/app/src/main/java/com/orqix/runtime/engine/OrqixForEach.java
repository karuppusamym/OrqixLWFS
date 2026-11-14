package com.orqix.runtime.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.variable.api.delegate.VariableScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.orqix.runtime.internal.RunEvents;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Expression bean {@code orqixForEach} and item result store (CG-E06, docs/build/S138). The compiler writes it into
 * steps with a {@code forEach} policy: Flowable multi-instance iterates {@link #items} (one instance per item, or per
 * item of the current batch from {@link #batches}); each item execution records its result here instead of in the
 * step variable ({@link #record}); the completion condition {@link #done} ends the step early once enough items
 * succeeded; and {@link #collect}, on the flow that leaves the step, writes the step outputs (item and completed
 * counts, stoppedEarly and the collected lists in item order). Rows live in {@code step_item_result}, in the engine
 * transaction, keyed by run, step, occurrence (the visit of the step) and item position.
 */
@Component("orqixForEach")
public class OrqixForEach {

    /** Hard cap of items per step occurrence (compiler: StepForEach.MAX_ITEMS). */
    public static final int MAX_ITEMS = 500;

    private final Json json;
    private final JdbcClient jdbc;
    private final RunEvents events;

    public OrqixForEach(Json json, JdbcClient jdbc, @org.springframework.context.annotation.Lazy RunEvents events) {
        this.json = json;
        this.jdbc = jdbc;
        this.events = events;
    }

    /** The step's policy as written by the compiler. */
    record Policy(String itemsField, int maxItems, boolean parallel, int batchSize, String when, int count,
                  Map<String, String> collect) {
        int target() {
            return "firstSuccess".equals(when) ? 1 : "atLeast".equals(when) ? count : 0;
        }
    }

    static Policy policy(String processDefinitionId, String nodeId) {
        ExtensionElement f = OrqixModel.requireExtension(OrqixModel.flowElement(processDefinitionId, nodeId), "forEach");
        String count = OrqixModel.attribute(f, "count");
        return new Policy(OrqixModel.attribute(f, "itemsRef").substring("/input/".length()),
                Integer.parseInt(OrqixModel.attribute(f, "maxItems")),
                "parallel".equals(OrqixModel.attribute(f, "mode")),
                Integer.parseInt(OrqixModel.attribute(f, "batchSize")), OrqixModel.attribute(f, "when"),
                count == null ? 0 : Integer.parseInt(count), OrqixModel.childMap(f, "collect", "result"));
    }

    /** @return true when the element is a for-each step */
    public static boolean isForEach(String processDefinitionId, String nodeId) {
        var element = OrqixModel.flowElement(processDefinitionId, nodeId);
        return element != null && OrqixModel.extension(element, "forEach").isPresent();
    }

    private List<JsonNode> list(VariableScope scope, Policy p) {
        JsonNode list = ProcessData.input(scope, json).path(p.itemsField());
        List<JsonNode> items = new ArrayList<>();
        list.forEach(items::add);
        if (items.size() > Math.min(p.maxItems(), MAX_ITEMS)) {
            // The compiler proves the request list cannot be longer; a longer list is a defect, never truncated.
            throw new IllegalStateException("The list " + p.itemsField() + " has " + items.size()
                    + " items, more than the step's limit of " + p.maxItems());
        }
        return items;
    }

    /**
     * Multi-instance collection: one JSON text {@code {"i": position, "v": item}} per item, or per item of the current
     * batch of a parallel for-each in batches.
     */
    public List<String> items(DelegateExecution execution, String nodeId) {
        Policy p = policy(execution.getProcessDefinitionId(), nodeId);
        List<JsonNode> all = list(execution, p);
        int from = 0;
        int to = all.size();
        Object batch = execution.getVariable(ProcessData.FOR_EACH_BATCH);
        if (batch instanceof Number b) {
            from = Math.min(all.size(), b.intValue() * p.batchSize());
            to = Math.min(all.size(), from + p.batchSize());
        }
        List<String> items = new ArrayList<>();
        for (int i = from; i < to; i++) {
            ObjectNode item = json.object();
            item.put("i", i);
            item.set("v", all.get(i));
            items.add(json.write(item));
        }
        if (batch == null || (batch instanceof Number b && b.intValue() == 0)) {
            events.append(ProcessData.workspaceId(execution), ProcessData.runId(execution), "FOR_EACH_STARTED", nodeId,
                    null, Map.of("items", all.size(), "parallel", p.parallel(), "batchSize", p.batchSize(),
                            "occurrence", ProcessData.occurrence(execution, nodeId)));
        }
        return items;
    }

    /** The items of the current batch of a parallel for-each call (S138). */
    public List<ProcessData.Item> batchItems(DelegateExecution execution, String nodeId) {
        List<ProcessData.Item> items = new ArrayList<>();
        for (String text : items(execution, nodeId)) {
            JsonNode item = json.parse(text);
            items.add(new ProcessData.Item(item.path("i").asInt(), item.path("v")));
        }
        return items;
    }

    /**
     * Runs the calls of one batch at the same time on virtual threads and returns their results in the same order.
     * Each call journals itself in its own transactions; nothing here touches the engine.
     */
    public <T> List<T> concurrently(List<java.util.concurrent.Callable<T>> calls) {
        if (calls.size() <= 1) {
            List<T> one = new ArrayList<>();
            for (var call : calls) {
                try {
                    one.add(call.call());
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return one;
        }
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<T>> futures = new ArrayList<>();
            for (var call : calls) {
                futures.add(pool.submit(call));
            }
            List<T> results = new ArrayList<>();
            for (var future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while sending the items of a batch", e);
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof RuntimeException r) {
                        throw r;
                    }
                    throw new IllegalStateException(e.getCause());
                }
            }
            return results;
        }
    }

    /** Records the result of one item of a batch (S138). */
    public void recordItem(DelegateExecution execution, String nodeId, int occurrence, int index, JsonNode result,
            boolean succeeded) {
        jdbc.sql("""
                INSERT INTO step_item_result (workspace_id, run_id, node_id, occurrence, item_index, succeeded, result)
                VALUES (:ws, :run, :node, :occ, :item, :ok, CAST(:result AS jsonb))
                ON CONFLICT (run_id, node_id, occurrence, item_index)
                DO UPDATE SET succeeded = EXCLUDED.succeeded, result = EXCLUDED.result, completed_at = now()
                """).param("ws", ProcessData.workspaceId(execution)).param("run", ProcessData.runId(execution))
                .param("node", nodeId).param("occ", occurrence).param("item", index)
                .param("ok", succeeded).param("result", json.write(result)).update();
    }

    /** Collection of the batches subprocess: the batch numbers 0..n-1. */
    public List<Integer> batches(DelegateExecution execution, String nodeId) {
        Policy p = policy(execution.getProcessDefinitionId(), nodeId);
        int size = list(execution, p).size();
        List<Integer> batches = new ArrayList<>();
        for (int b = 0; b * p.batchSize() < size; b++) {
            batches.add(b);
        }
        return batches;
    }

    /** Completion condition: enough items of this visit succeeded. */
    public boolean done(DelegateExecution execution, String nodeId) {
        Policy p = policy(execution.getProcessDefinitionId(), nodeId);
        if (p.target() <= 0) {
            return false;
        }
        return succeeded(ProcessData.runId(execution), nodeId, ProcessData.occurrence(execution, nodeId)) >= p.target();
    }

    /**
     * Records the result of the current item (called by the step's delegate instead of writing the step variable).
     *
     * @return false when the execution is not a for-each item, so the caller writes the step variable as usual
     */
    public boolean record(DelegateExecution execution, String nodeId, JsonNode result, boolean succeeded) {
        var item = ProcessData.item(execution, json);
        if (item.isEmpty()) {
            return false;
        }
        int occurrence = ProcessData.occurrence(execution, nodeId);
        jdbc.sql("""
                INSERT INTO step_item_result (workspace_id, run_id, node_id, occurrence, item_index, succeeded, result)
                VALUES (:ws, :run, :node, :occ, :item, :ok, CAST(:result AS jsonb))
                ON CONFLICT (run_id, node_id, occurrence, item_index)
                DO UPDATE SET succeeded = EXCLUDED.succeeded, result = EXCLUDED.result, completed_at = now()
                """).param("ws", ProcessData.workspaceId(execution)).param("run", ProcessData.runId(execution))
                .param("node", nodeId).param("occ", occurrence).param("item", item.get().index())
                .param("ok", succeeded).param("result", json.write(result)).update();
        return true;
    }

    /** Leaving the step: its outputs are the counts and the collected lists, in item order. */
    public void collect(DelegateExecution execution, String nodeId) {
        Policy p = policy(execution.getProcessDefinitionId(), nodeId);
        UUID runId = ProcessData.runId(execution);
        int occurrence = ProcessData.occurrence(execution, nodeId);
        int count = list(execution, p).size();
        Map<Integer, JsonNode> results = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT item_index, result FROM step_item_result
                WHERE run_id = :run AND node_id = :node AND occurrence = :occ ORDER BY item_index
                """).param("run", runId).param("node", nodeId).param("occ", occurrence)
                .query((rs, n) -> results.put(rs.getInt("item_index"), json.parse(rs.getString("result")))).list();
        ObjectNode out = json.object();
        out.put("itemCount", count);
        out.put("completedCount", results.size());
        out.put("stoppedEarly", results.size() < count);
        p.collect().forEach((name, result) -> {
            ArrayNode values = out.putArray(name);
            for (int i = 0; i < count; i++) {
                JsonNode r = results.get(i);
                JsonNode v = r == null ? null : r.get(result);
                if (v == null || v.isMissingNode()) {
                    values.addNull();
                } else {
                    values.add(v);
                }
            }
        });
        execution.setVariable(ProcessData.stepVariable(nodeId), json.write(out));
        events.append(ProcessData.workspaceId(execution), runId, "FOR_EACH_COMPLETED", nodeId, null,
                Map.of("items", count, "completed", results.size(),
                        "succeeded", succeeded(runId, nodeId, occurrence), "occurrence", occurrence));
    }

    private int succeeded(UUID runId, String nodeId, int occurrence) {
        return jdbc.sql("""
                SELECT count(*) FROM step_item_result
                WHERE run_id = :run AND node_id = :node AND occurrence = :occ AND succeeded
                """).param("run", runId).param("node", nodeId).param("occ", occurrence).query(Integer.class).single();
    }

    /** @return the item results of one step visit, for the run view (S138) */
    public List<Map<String, Object>> results(UUID workspaceId, UUID runId, String nodeId) {
        return jdbc.sql("""
                SELECT occurrence, item_index, succeeded, completed_at FROM step_item_result
                WHERE workspace_id = :ws AND run_id = :run AND node_id = :node ORDER BY occurrence, item_index
                """).param("ws", workspaceId).param("run", runId).param("node", nodeId).query((rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("occurrence", rs.getInt("occurrence"));
                    row.put("itemIndex", rs.getInt("item_index"));
                    row.put("succeeded", rs.getBoolean("succeeded"));
                    row.put("completedAt", rs.getTimestamp("completed_at").toInstant());
                    return row;
                }).list();
    }

    static BigDecimal number(int value) {
        return BigDecimal.valueOf(value);
    }
}
