package com.orqix.runtime.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.AdminService;
import com.orqix.identity.Caller;
import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.AdmissionControl;
import com.orqix.runtime.internal.AdmissionSettings;
import com.orqix.shared.problem.ApiException;

/** CG-P05 admission limits per workspace (docs/build/S150), platform administrators only (tag Platform). */
@RestController
@RequestMapping("/api/v1/admin")
public class AdmissionController {

    public record Limits(int startsPerMinute, int messagesPerMinute) {
    }

    public record Override(Integer startsPerMinute, Integer messagesPerMinute, int revision, UUID updatedBy,
            Instant updatedAt) {
    }

    public record WorkspaceAdmission(UUID workspaceId, boolean enabled, Limits effective, Override override) {
    }

    public record AdmissionOverview(boolean enabled, Limits defaults, List<WorkspaceAdmission> overrides) {
    }

    /** Both null clears the override; 0 means no limit for the workspace. */
    public record SetAdmissionRequest(Integer startsPerMinute, Integer messagesPerMinute) {
    }

    private final CallerResolver callers;
    private final AdminService admin;
    private final AdmissionControl admission;

    public AdmissionController(CallerResolver callers, AdminService admin, AdmissionControl admission) {
        this.callers = callers;
        this.admin = admin;
        this.admission = admission;
    }

    @GetMapping("/admission")
    public AdmissionOverview listAdmissionLimits() {
        admin.requirePlatformAdmin(callers.current(), "listAdmissionLimits", false);
        List<WorkspaceAdmission> overrides = admission.overrides().stream()
                .map(e -> view(e.getKey(), e.getValue())).toList();
        return new AdmissionOverview(admission.settings().enabled(), new Limits(admission.settings().startsPerMinute(),
                admission.settings().messagesPerMinute()), overrides);
    }

    @GetMapping("/workspaces/{workspaceId}/admission")
    public WorkspaceAdmission getWorkspaceAdmission(@PathVariable UUID workspaceId) {
        admin.requirePlatformAdmin(callers.current(), "getWorkspaceAdmission", false);
        admission.requireWorkspace(workspaceId);
        return view(workspaceId, admission.override(workspaceId).orElse(null));
    }

    @PutMapping("/workspaces/{workspaceId}/admission")
    public WorkspaceAdmission setWorkspaceAdmission(@PathVariable UUID workspaceId,
            @RequestBody SetAdmissionRequest request) {
        Caller caller = callers.current();
        // Authorization before validation: anyone else gets 403 whatever they sent (security matrix).
        admin.requirePlatformAdmin(caller, "setWorkspaceAdmission", true);
        for (Integer value : java.util.Arrays.asList(request.startsPerMinute(), request.messagesPerMinute())) {
            if (value != null && (value < 0 || value > AdmissionSettings.MAX_LIMIT)) {
                throw ApiException.malformed("Limits are 0 (no limit) to " + AdmissionSettings.MAX_LIMIT + " per minute.");
            }
        }
        AdmissionControl.Override stored = admission.update(workspaceId, caller.subjectId(), request.startsPerMinute(),
                request.messagesPerMinute()).orElse(null);
        return view(workspaceId, stored);
    }

    private WorkspaceAdmission view(UUID workspaceId, AdmissionControl.Override o) {
        AdmissionControl.Limits effective = admission.effective(o);
        return new WorkspaceAdmission(workspaceId, admission.settings().enabled(),
                new Limits(effective.startsPerMinute(), effective.messagesPerMinute()),
                o == null ? null : new Override(o.startsPerMinute(), o.messagesPerMinute(), o.revision(), o.updatedBy(),
                        o.updatedAt()));
    }
}
