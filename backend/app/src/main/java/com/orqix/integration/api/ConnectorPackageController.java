package com.orqix.integration.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.integration.packages.ConnectorPackageService;
import com.orqix.integration.packages.PackageTriggerService;

/** Connector packages, the internal catalog, the palette and package triggers (docs/build/S133). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class ConnectorPackageController {

    private final CallerResolver callers;
    private final ConnectorPackageService packages;
    private final PackageTriggerService triggers;

    public ConnectorPackageController(CallerResolver callers, ConnectorPackageService packages,
            PackageTriggerService triggers) {
        this.callers = callers;
        this.packages = packages;
        this.triggers = triggers;
    }

    @GetMapping("/connector-packages")
    public ConnectorPackageService.PackageList listConnectorPackages(@PathVariable UUID workspaceId) {
        return packages.list(callers.current(), workspaceId);
    }

    @PostMapping("/connector-packages")
    public ResponseEntity<ConnectorPackageService.Detail> importConnectorPackage(@PathVariable UUID workspaceId,
            @RequestBody ConnectorPackageService.ImportRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(packages.importPackage(callers.current(), workspaceId, request));
    }

    @GetMapping("/connector-packages/{packageVersionId}")
    public ConnectorPackageService.Detail getConnectorPackage(@PathVariable UUID workspaceId,
            @PathVariable UUID packageVersionId) {
        return packages.get(callers.current(), workspaceId, packageVersionId);
    }

    @PostMapping("/connector-packages/{packageVersionId}/decision")
    public ConnectorPackageService.Detail decideConnectorPackage(@PathVariable UUID workspaceId,
            @PathVariable UUID packageVersionId, @RequestBody ConnectorPackageService.DecisionRequest request) {
        return packages.decide(callers.current(), workspaceId, packageVersionId, request);
    }

    @PostMapping("/connector-packages/{packageVersionId}/install")
    public ResponseEntity<ConnectorPackageService.InstallResult> installConnectorPackage(@PathVariable UUID workspaceId,
            @PathVariable UUID packageVersionId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(packages.install(callers.current(), workspaceId,
                packageVersionId));
    }

    @PostMapping("/connector-packages/{packageVersionId}/retire")
    public ConnectorPackageService.Detail retireConnectorPackage(@PathVariable UUID workspaceId,
            @PathVariable UUID packageVersionId, @RequestBody ConnectorPackageService.RetireRequest request) {
        return packages.retire(callers.current(), workspaceId, packageVersionId, request);
    }

    @GetMapping("/connector-catalog")
    public ConnectorPackageService.Catalog getConnectorCatalog(@PathVariable UUID workspaceId) {
        return packages.catalog(callers.current(), workspaceId);
    }

    @GetMapping("/connector-palette")
    public ConnectorPackageService.Palette searchConnectorPalette(@PathVariable UUID workspaceId,
            @RequestParam(name = "q", required = false) String q) {
        return packages.palette(callers.current(), workspaceId, q);
    }

    @GetMapping("/package-triggers")
    public PackageTriggerService.TriggerList listPackageTriggers(@PathVariable UUID workspaceId) {
        return triggers.list(callers.current(), workspaceId);
    }

    @PostMapping("/package-triggers")
    public ResponseEntity<PackageTriggerService.TriggerView> createPackageTrigger(@PathVariable UUID workspaceId,
            @RequestBody PackageTriggerService.ConfigureRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(triggers.create(callers.current(), workspaceId, request));
    }

    @PutMapping("/package-triggers/{packageTriggerId}")
    public PackageTriggerService.TriggerView updatePackageTrigger(@PathVariable UUID workspaceId,
            @PathVariable UUID packageTriggerId, @RequestBody PackageTriggerService.ConfigureRequest request) {
        return triggers.update(callers.current(), workspaceId, packageTriggerId, request);
    }

    @GetMapping("/package-triggers/{packageTriggerId}/dead-records")
    public PackageTriggerService.DeadRecordList listPackageTriggerDeadRecords(@PathVariable UUID workspaceId,
            @PathVariable UUID packageTriggerId) {
        return triggers.dead(callers.current(), workspaceId, packageTriggerId);
    }

    /** Verified by the trigger's scheme alone; no session or token is read here. */
    @PostMapping("/package-triggers/{packageTriggerId}/events")
    public ResponseEntity<PackageTriggerService.Accepted> receivePackageTriggerEvent(@PathVariable UUID workspaceId,
            @PathVariable String packageTriggerId, @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        java.util.Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(triggers.receive(workspaceId, packageTriggerId, headers,
                body, request.isSecure()));
    }
}
