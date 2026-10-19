package com.orqix.integration.api;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import com.orqix.identity.CallerResolver;
import com.orqix.integration.DelegatedOAuth;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/connections/{connectionKey}/oauth")
public class ConnectionOAuthController {
    private final CallerResolver callers;
    private final DelegatedOAuth oauth;
    public ConnectionOAuthController(CallerResolver callers, DelegatedOAuth oauth) { this.callers=callers; this.oauth=oauth; }

    @GetMapping
    public DelegatedOAuth.Status status(@PathVariable UUID workspaceId, @PathVariable String connectionKey) {
        return oauth.status(callers.current(),workspaceId,connectionKey);
    }
    @PostMapping("/authorize")
    public DelegatedOAuth.Authorization begin(@PathVariable UUID workspaceId, @PathVariable String connectionKey) {
        String callback=ServletUriComponentsBuilder.fromCurrentContextPath().path("/w/{ws}/settings/connections/{key}/oauth/callback")
                .buildAndExpand(workspaceId,connectionKey).toUriString();
        return oauth.begin(callers.current(),workspaceId,connectionKey,callback);
    }
    @PostMapping("/complete")
    public DelegatedOAuth.Status finish(@PathVariable UUID workspaceId, @PathVariable String connectionKey,
            @RequestBody DelegatedOAuth.Finish request) {
        return oauth.finish(callers.current(),workspaceId,connectionKey,request);
    }
    @PostMapping("/disconnect")
    public DelegatedOAuth.Status disconnect(@PathVariable UUID workspaceId, @PathVariable String connectionKey) {
        return oauth.disconnect(callers.current(),workspaceId,connectionKey);
    }
}
