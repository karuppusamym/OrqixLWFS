package com.orqix.runtime.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.MappedMigrations;
import com.orqix.runtime.internal.MigrationBatches;
import com.orqix.runtime.internal.OperatorService;
import com.orqix.runtime.internal.RunMoves;
import com.orqix.shared.idempotency.IdempotencyService;

/** Mapped release migration, migration batches and guarded moves (contracts/openapi.yaml, tag MigrationAndMove). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class MigrationAndMoveController {

    private final CallerResolver callers;
    private final MappedMigrations migrations;
    private final MigrationBatches batches;
    private final RunMoves moves;

    public MigrationAndMoveController(CallerResolver callers, MappedMigrations migrations, MigrationBatches batches,
            RunMoves moves) {
        this.callers = callers;
        this.migrations = migrations;
        this.batches = batches;
        this.moves = moves;
    }

    @PostMapping("/runs/{runId}/migration-plan")
    public MigrationMoveDtos.MigrationPlan planRunMigration(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @Valid @RequestBody MigrationMoveDtos.MigrationPlanRequest request) {
        return migrations.plan(callers.current(), workspaceId, runId, request);
    }

    @PostMapping("/runs/{runId}/migrate-with-mapping")
    public ResponseEntity<RuntimeDtos.RunDetail> migrateRunWithMapping(@PathVariable UUID workspaceId,
            @PathVariable UUID runId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody MigrationMoveDtos.MappedMigrationRequest request) {
        return replayAware(migrations.migrate(callers.current(), workspaceId, runId, idempotencyKey, request));
    }

    @PostMapping("/migration-batches/preview")
    public MigrationMoveDtos.MigrationBatchPreview previewMigrationBatch(@PathVariable UUID workspaceId,
            @Valid @RequestBody MigrationMoveDtos.MigrationBatchPreviewRequest request) {
        return batches.preview(callers.current(), workspaceId, request);
    }

    @PostMapping("/migration-batches")
    public ResponseEntity<MigrationMoveDtos.MigrationBatch> createMigrationBatch(@PathVariable UUID workspaceId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody MigrationMoveDtos.CreateMigrationBatchRequest request) {
        return replayAware(batches.create(callers.current(), workspaceId, idempotencyKey, request));
    }

    @GetMapping("/migration-batches/{batchId}")
    public MigrationMoveDtos.MigrationBatch getMigrationBatch(@PathVariable UUID workspaceId, @PathVariable UUID batchId) {
        return batches.get(callers.current(), workspaceId, batchId);
    }

    @PostMapping("/runs/{runId}/move-plan")
    public MigrationMoveDtos.MovePlan planRunMove(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @Valid @RequestBody MigrationMoveDtos.MovePlanRequest request) {
        return moves.plan(callers.current(), workspaceId, runId, request);
    }

    @GetMapping("/runs/{runId}/moves")
    public MigrationMoveDtos.RunMoveList listRunMoves(@PathVariable UUID workspaceId, @PathVariable UUID runId) {
        return moves.list(callers.current(), workspaceId, runId);
    }

    @PostMapping("/runs/{runId}/moves")
    public ResponseEntity<MigrationMoveDtos.RunMove> moveRun(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody MigrationMoveDtos.MoveRunRequest request) {
        return replayAware(moves.request(callers.current(), workspaceId, runId, idempotencyKey, request));
    }

    @PostMapping("/runs/{runId}/moves/{moveId}/approve")
    public ResponseEntity<MigrationMoveDtos.RunMove> approveRunMove(@PathVariable UUID workspaceId,
            @PathVariable UUID runId, @PathVariable UUID moveId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody MigrationMoveDtos.DecideMoveRequest request) {
        return replayAware(moves.approve(callers.current(), workspaceId, runId, moveId, idempotencyKey, request));
    }

    @PostMapping("/runs/{runId}/moves/{moveId}/reject")
    public ResponseEntity<MigrationMoveDtos.RunMove> rejectRunMove(@PathVariable UUID workspaceId,
            @PathVariable UUID runId, @PathVariable UUID moveId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody MigrationMoveDtos.DecideMoveRequest request) {
        return replayAware(moves.reject(callers.current(), workspaceId, runId, moveId, idempotencyKey, request));
    }

    private static <T> ResponseEntity<T> replayAware(OperatorService.Result<T> result) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (result.replayed()) {
            builder.header(IdempotencyService.REPLAYED_HEADER, "true");
        }
        return builder.body(result.body());
    }
}
