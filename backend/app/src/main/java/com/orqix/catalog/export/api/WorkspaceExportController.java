package com.orqix.catalog.export.api;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import com.orqix.catalog.export.WorkspaceExportService;
import com.orqix.identity.CallerResolver;

/**
 * Workflow exit export (exportWorkspaceArchive in contracts/openapi.yaml). The ZIP is written straight to the
 * response while it is produced, so history of any size is streamed instead of being built in memory first.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class WorkspaceExportController {

    /** Body of the export request; both fields optional. */
    public record ExitExportRequest(@Size(max = 500) List<UUID> workflowIds, Boolean includeRunData) {
    }

    private final WorkspaceExportService exports;
    private final CallerResolver callers;

    public WorkspaceExportController(WorkspaceExportService exports, CallerResolver callers) {
        this.exports = exports;
        this.callers = callers;
    }

    @PostMapping("/exit-export")
    public void exportWorkspaceArchive(@PathVariable UUID workspaceId,
            @Valid @RequestBody(required = false) ExitExportRequest request, HttpServletResponse response)
            throws IOException {
        WorkspaceExportService.ExportRequest export = new WorkspaceExportService.ExportRequest(
                request == null ? null : request.workflowIds(),
                request != null && Boolean.TRUE.equals(request.includeRunData()));
        WorkspaceExportService.Plan plan = exports.prepare(callers.current(), workspaceId, export);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(plan.fileName()).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        OutputStream out = response.getOutputStream();
        exports.write(plan, out);
        out.flush();
    }
}
