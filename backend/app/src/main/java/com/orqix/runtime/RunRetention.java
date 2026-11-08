package com.orqix.runtime;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.runtime.engine.EngineHistoryPurge;
import com.orqix.runtime.evidencepack.RunEvidencePack;
import com.orqix.runtime.internal.RunStore;

/**
 * What run retention (CG-O07, CG-P04, docs/build/S144) needs from the runtime: the request rows of a hierarchy, the
 * system evidence pack for the archive and the engine history purge. Deleting operational rows is not here: it is the
 * database function {@code orqix_purge_run}, which re-checks every guard itself.
 */
@Service
public class RunRetention {

    public record Run(UUID id, String reference, UUID workflowId, UUID parentRunId, String lifecycle, Instant endedAt,
            String engineProcessInstanceId, int callDepth) {

        public boolean ended() {
            return endedAt != null;
        }
    }

    public record HistoryPurged(List<String> purged, List<String> skipped, int instancesDeleted) {
    }

    private final RunStore runs;
    private final RunEvidencePack evidence;
    private final EngineHistoryPurge history;

    public RunRetention(RunStore runs, RunEvidencePack evidence, EngineHistoryPurge history) {
        this.runs = runs;
        this.evidence = evidence;
        this.history = history;
    }

    @Transactional(readOnly = true)
    public Optional<Run> find(UUID workspaceId, UUID runId) {
        return runs.find(workspaceId, runId).map(RunRetention::view);
    }

    /** Every descendant of the request, deepest first (the purge order). */
    @Transactional(readOnly = true)
    public List<Run> descendantsDeepestFirst(UUID workspaceId, UUID runId) {
        return runs.descendantsDeepestFirst(workspaceId, runId).stream().map(RunRetention::view).toList();
    }

    /** The evidence pack of the request rendered as {@code json} and {@code pdf}. */
    @Transactional(readOnly = true)
    public Map<String, byte[]> evidencePack(UUID workspaceId, String workspaceKey, String workspaceName, UUID runId) {
        RunStore.RunRow run = runs.find(workspaceId, runId).orElseThrow();
        return evidence.archive(workspaceId, workspaceKey, workspaceName, run);
    }

    /** Deletes the engine history of ended process instances in the caller's transaction (or its own). */
    @Transactional
    public HistoryPurged purgeEngineHistory(Collection<String> processInstanceIds) {
        EngineHistoryPurge.Result result = history.purge(processInstanceIds);
        return new HistoryPurged(result.purged(), result.skipped(), result.instancesDeleted());
    }

    private static Run view(RunStore.RunRow r) {
        return new Run(r.id(), r.reference(), r.workflowId(), r.parentRunId(), r.lifecycle(), r.endedAt(),
                r.engineProcessInstanceId(), r.callDepth());
    }
}
