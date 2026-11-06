package com.orqix.mcp.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.mcp.McpResource;
import com.orqix.mcp.McpSettings;

/**
 * OAuth 2.0 Protected Resource Metadata (RFC 9728) of the MCP server, at the well-known URL derived from the resource
 * URI ({@code /.well-known/oauth-protected-resource/mcp}) and at the host root. Public; it names the authorization
 * server(s) that issue tokens for {@code /mcp}.
 */
@RestController
public class McpMetadataController {

    private final McpSettings settings;

    public McpMetadataController(McpSettings settings) {
        this.settings = settings;
    }

    @GetMapping({McpResource.METADATA_PATH, McpResource.METADATA_PATH + McpResource.PATH})
    public ResponseEntity<Map<String, Object>> metadata(HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resource", McpResource.uri(settings, request));
        body.put("authorization_servers", settings.announcedAuthorizationServers());
        body.put("bearer_methods_supported", List.of("header"));
        body.put("scopes_supported", settings.scopes());
        body.put("resource_name", "Orqix");
        return ResponseEntity.ok().cacheControl(CacheControl.noCache())
                .header("Access-Control-Allow-Origin", "*").body(body);
    }
}
