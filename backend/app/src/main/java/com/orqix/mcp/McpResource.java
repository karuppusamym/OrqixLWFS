package com.orqix.mcp;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Canonical URIs of the MCP endpoint and its protected resource metadata (RFC 9728 section 3.1). */
public final class McpResource {

    public static final String PATH = "/mcp";
    public static final String METADATA_PATH = "/.well-known/oauth-protected-resource";

    private McpResource() {
    }

    /** The resource URI: configured, else this server's origin (forwarded headers honoured) plus {@code /mcp}. */
    public static String uri(McpSettings settings, HttpServletRequest request) {
        if (!settings.resourceUri().isBlank()) {
            String value = settings.resourceUri();
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        }
        return ServletUriComponentsBuilder.fromContextPath(request).path(PATH).replaceQuery(null).build().toUriString();
    }

    /** Metadata URL of the resource: the well-known prefix inserted before the resource path. */
    public static String metadataUri(McpSettings settings, HttpServletRequest request) {
        URI resource = URI.create(uri(settings, request));
        String path = resource.getRawPath() == null ? "" : resource.getRawPath();
        return resource.getScheme() + "://" + resource.getRawAuthority() + METADATA_PATH + path;
    }
}
