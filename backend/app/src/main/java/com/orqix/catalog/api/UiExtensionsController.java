package com.orqix.catalog.api;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.catalog.internal.UiExtensionFrame;
import com.orqix.catalog.internal.UiExtensions;
import com.orqix.identity.CallerResolver;

/** S111 UI extension registry, admin decisions and the sandboxed frame document. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/ui-extensions")
public class UiExtensionsController {
    private final UiExtensions extensions;
    private final CallerResolver callers;

    public UiExtensionsController(UiExtensions extensions, CallerResolver callers) {
        this.extensions = extensions;
        this.callers = callers;
    }

    @GetMapping
    public UiExtensions.ExtensionList list(@PathVariable UUID workspaceId) {
        return extensions.list(callers.current(), workspaceId);
    }

    @PostMapping
    public ResponseEntity<UiExtensions.Extension> create(@PathVariable UUID workspaceId,
            @RequestBody UiExtensions.Create request) {
        return ResponseEntity.status(201).body(extensions.create(callers.current(), workspaceId, request));
    }

    @PostMapping("/{extensionId}/versions")
    public ResponseEntity<UiExtensions.VersionDetail> submit(@PathVariable UUID workspaceId, @PathVariable UUID extensionId,
            @RequestBody UiExtensions.Submit request) {
        return ResponseEntity.status(201).body(extensions.submit(callers.current(), workspaceId, extensionId, request));
    }

    @GetMapping("/{extensionId}/versions/{version}")
    public UiExtensions.VersionDetail version(@PathVariable UUID workspaceId, @PathVariable UUID extensionId,
            @PathVariable int version) {
        return extensions.version(callers.current(), workspaceId, extensionId, version);
    }

    @PostMapping("/{extensionId}/versions/{version}/decision")
    public UiExtensions.VersionDetail decide(@PathVariable UUID workspaceId, @PathVariable UUID extensionId,
            @PathVariable int version, @RequestBody UiExtensions.Decide request) {
        return extensions.decide(callers.current(), workspaceId, extensionId, version, request);
    }

    /** Replaces the application-wide policy and frame options for this one response; nothing else is relaxed. */
    @GetMapping("/{extensionId}/versions/{version}/frame")
    public ResponseEntity<String> frame(@PathVariable UUID workspaceId, @PathVariable UUID extensionId,
            @PathVariable int version) {
        String origin = org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromCurrentContextPath()
                .build().toUriString();
        UiExtensionFrame.Document document = extensions.frame(callers.current(), workspaceId, extensionId, version, origin);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", document.policy())
                .header("X-Frame-Options", "SAMEORIGIN")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .header("Permissions-Policy", UiExtensionFrame.PERMISSIONS)
                .header("Referrer-Policy", "no-referrer")
                .body(document.html());
    }

    /**
     * A file of a multi-file bundle (S116), requested by the sandboxed frame itself. Such requests carry no session, so
     * the short-lived signed path minted by the frame endpoint is the credential. Cross-origin readable (the frame has an
     * opaque origin and loads modules with CORS); never cached by shared caches.
     */
    @GetMapping("/{extensionId}/versions/{version}/files/{token}/{path}")
    public ResponseEntity<byte[]> file(@PathVariable UUID workspaceId, @PathVariable UUID extensionId,
            @PathVariable int version, @PathVariable String token, @PathVariable String path) {
        var file = extensions.file(workspaceId, extensionId, version, token, path);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePrivate())
                .header("Access-Control-Allow-Origin", "*")
                .header("Cross-Origin-Resource-Policy", "cross-origin")
                .header("X-Content-Type-Options", "nosniff")
                .body(file.content());
    }
}
