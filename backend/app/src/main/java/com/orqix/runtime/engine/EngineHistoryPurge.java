package com.orqix.runtime.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.flowable.dmn.api.DmnHistoryService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * CG-P04 (docs/build/S144): deletes the engine history (ACT_HI_* and the DMN decision history) of ended process
 * instances whose request was archived and purged. Only the Flowable API is used, so the engine's own delete order
 * (variables, details, activities, tasks, identity links, comments, entity links, byte arrays) is kept. An instance
 * that still has runtime state, or whose history has no end time, is skipped and reported, never deleted. Sub process
 * instances (call activities inside one release) are deleted with their parent.
 */
@Component
public class EngineHistoryPurge {

    /** {@code purged} includes instances that had no history left; {@code skipped} still run or never ended. */
    public record Result(List<String> purged, List<String> skipped, int instancesDeleted) {
    }

    private final HistoryService history;
    private final RuntimeService runtime;
    private final ObjectProvider<DmnHistoryService> dmnHistory;

    public EngineHistoryPurge(HistoryService history, RuntimeService runtime, ObjectProvider<DmnHistoryService> dmnHistory) {
        this.history = history;
        this.runtime = runtime;
        this.dmnHistory = dmnHistory;
    }

    @Transactional
    public Result purge(Collection<String> processInstanceIds) {
        List<String> purged = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<String> delete = new LinkedHashSet<>();
        for (String id : processInstanceIds) {
            if (runtime.createProcessInstanceQuery().processInstanceId(id).count() > 0) {
                skipped.add(id);
                continue;
            }
            HistoricProcessInstance root = history.createHistoricProcessInstanceQuery().processInstanceId(id)
                    .singleResult();
            if (root == null) {
                purged.add(id);
                continue;
            }
            if (root.getEndTime() == null) {
                skipped.add(id);
                continue;
            }
            Set<String> tree = new LinkedHashSet<>();
            if (collect(id, tree)) {
                delete.addAll(tree);
                purged.add(id);
            } else {
                skipped.add(id);
            }
        }
        if (!delete.isEmpty()) {
            DmnHistoryService dmn = dmnHistory.getIfAvailable();
            if (dmn != null) {
                dmn.bulkDeleteHistoricDecisionExecutionsByInstanceIdsAndScopeType(delete, "bpmn");
            }
            history.bulkDeleteHistoricProcessInstances(delete);
        }
        return new Result(purged, skipped, delete.size());
    }

    /** Adds the instance and its ended sub instances; false when one of them has not ended. */
    private boolean collect(String id, Set<String> tree) {
        if (!tree.add(id) || tree.size() > 1_000) {
            return tree.size() <= 1_000;
        }
        for (HistoricProcessInstance sub : history.createHistoricProcessInstanceQuery().superProcessInstanceId(id).list()) {
            if (sub.getEndTime() == null || runtime.createProcessInstanceQuery().processInstanceId(sub.getId()).count() > 0) {
                return false;
            }
            if (!collect(sub.getId(), tree)) {
                return false;
            }
        }
        return true;
    }
}
