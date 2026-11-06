package com.orqix.runtime.api;

import java.io.IOException;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.FormLookups;
import com.orqix.runtime.internal.GuestResponseService;
import com.orqix.runtime.internal.RunAttachments;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.problem.ApiException;

import tools.jackson.databind.JsonNode;

/**
 * Lookup options (CG-F02) and guest response forms (CG-F05), docs/build/S140. Operators list, issue, send and revoke
 * guest links; the guest reads the form, adds files and submits with the link token alone
 * ({@code X-Orqix-Guest-Token}), on the stateless route without session or CSRF (like the S98 callback routes).
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class GuestResponseController {

    private final CallerResolver callers;
    private final GuestResponseService guests;
    private final FormLookups lookups;

    public GuestResponseController(CallerResolver callers, GuestResponseService guests, FormLookups lookups) {
        this.callers = callers;
        this.guests = guests;
        this.lookups = lookups;
    }

    // ---------------------------------------------------------------- lookups

    @PostMapping("/workflows/{workflowId}/lookups/{fieldKey}")
    public ResponseEntity<LookupDtos.LookupOptions> searchLookupOptions(@PathVariable UUID workspaceId,
            @PathVariable UUID workflowId, @PathVariable String fieldKey,
            @RequestBody(required = false) LookupDtos.LookupSearch body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(lookups.search(callers.current(), workspaceId, workflowId, fieldKey, body));
    }

    // ---------------------------------------------------------------- operators

    @GetMapping("/guest-links")
    public GuestDtos.GuestLinkList listGuestLinks(@PathVariable UUID workspaceId,
            @RequestParam(required = false) UUID runId) {
        return guests.list(callers.current(), workspaceId, runId);
    }

    @PostMapping("/guest-links/{linkId}/token")
    public ResponseEntity<GuestDtos.GuestLinkIssued> issueGuestLink(@PathVariable UUID workspaceId,
            @PathVariable UUID linkId) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(guests.issue(callers.current(), workspaceId, linkId));
    }

    @DeleteMapping("/guest-links/{linkId}/token")
    public ResponseEntity<Void> revokeGuestLink(@PathVariable UUID workspaceId, @PathVariable UUID linkId) {
        guests.revoke(callers.current(), workspaceId, linkId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/guest-links/{linkId}/send")
    public GuestDtos.GuestLink sendGuestLink(@PathVariable UUID workspaceId, @PathVariable UUID linkId) {
        return guests.send(callers.current(), workspaceId, linkId);
    }

    @GetMapping("/guest-domains")
    public GuestDtos.GuestDomains listGuestDomains(@PathVariable UUID workspaceId) {
        return guests.domains(callers.current(), workspaceId);
    }

    @PutMapping("/guest-domains")
    public GuestDtos.GuestDomains replaceGuestDomains(@PathVariable UUID workspaceId,
            @RequestBody GuestDtos.GuestDomains body) {
        return guests.replaceDomains(callers.current(), workspaceId, body);
    }

    // ---------------------------------------------------------------- the guest (token only)

    @GetMapping("/guest-response")
    public ResponseEntity<GuestDtos.GuestForm> getGuestForm(@PathVariable UUID workspaceId,
            @RequestHeader(name = GuestResponseService.TOKEN_HEADER, required = false) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(guests.form(workspaceId, token));
    }

    @PostMapping(path = "/guest-response/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GuestDtos.GuestFile> uploadGuestFile(@PathVariable UUID workspaceId,
            @RequestHeader(name = GuestResponseService.TOKEN_HEADER, required = false) String token,
            @RequestPart(name = "file", required = false) MultipartFile file) {
        RunAttachments.Upload upload = null;
        if (file != null) {
            try {
                upload = new RunAttachments.Upload(file.getOriginalFilename(), file.getContentType(),
                        file.getInputStream());
            } catch (IOException e) {
                throw ApiException.malformed("The file could not be read.");
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(guests.upload(workspaceId, token, upload));
    }

    @PostMapping("/guest-response/submit")
    public ResponseEntity<GuestDtos.GuestSubmitted> submitGuestResponse(@PathVariable UUID workspaceId,
            @RequestHeader(name = GuestResponseService.TOKEN_HEADER, required = false) String token,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        GuestResponseService.Submit submit = guests.submit(workspaceId, token, idempotencyKey, body);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (submit.replayed()) {
            builder.header(IdempotencyService.REPLAYED_HEADER, "true");
        }
        return builder.body(submit.body());
    }
}
