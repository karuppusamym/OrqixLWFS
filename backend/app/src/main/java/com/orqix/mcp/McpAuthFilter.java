package com.orqix.mcp;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import com.orqix.identity.Caller;
import com.orqix.identity.CallerResolver;
import com.orqix.shared.problem.ApiException;

/**
 * Authentication of {@code /mcp} (S136 section 2, MCP authorization specification): a bearer access token in the
 * Authorization header (never in the URL) that the identity provider issued for this resource. Without a usable token
 * the answer is 401 with {@code WWW-Authenticate: Bearer resource_metadata="..."} so the client can discover the
 * authorization server (RFC 9728 section 5.1). Browser origins other than the allowed ones are refused (DNS rebinding).
 * Not a servlet filter bean: it runs only inside the MCP security chain.
 */
public final class McpAuthFilter extends OncePerRequestFilter {

    /** Request attribute holding the {@link Principal} of an authenticated MCP request. */
    public static final String ATTRIBUTE = "orqix.mcp.principal";

    /** The person behind the token, the OAuth client they use, and when the token ends. */
    public record Principal(Caller caller, String client, Instant expiresAt) {
    }

    private final McpTokens tokens;
    private final CallerResolver callers;
    private final McpSettings settings;

    public McpAuthFilter(McpTokens tokens, CallerResolver callers, McpSettings settings) {
        this.tokens = tokens;
        this.callers = callers;
        this.settings = settings;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(request.getContextPath() + McpResource.METADATA_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && !originAllowed(origin, request)) {
            write(response, 403, null, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,"
                    + "\"message\":\"This origin may not call the Orqix MCP server.\"}}");
            return;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7) || header.length() <= 7) {
            challenge(request, response, null, null);
            return;
        }
        Jwt jwt;
        try {
            jwt = tokens.verify(header.substring(7).trim());
        } catch (McpTokens.InvalidToken e) {
            challenge(request, response, "invalid_token", e.getMessage());
            return;
        }
        if (!tokens.audienceMatches(jwt, McpResource.uri(settings, request))) {
            challenge(request, response, "invalid_token", "The access token was not issued for this resource.");
            return;
        }
        Caller caller;
        try {
            caller = callers.fromDelegatedToken(jwt);
        } catch (ApiException e) {
            if (e.code().status() == 401) {
                challenge(request, response, "invalid_token", "The access token has no subject.");
            } else {
                write(response, 403, null, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,"
                        + "\"message\":\"This account is disabled.\"}}");
            }
            return;
        }
        String client = jwt.getClaimAsString("azp") != null ? jwt.getClaimAsString("azp") : jwt.getClaimAsString("client_id");
        request.setAttribute(ATTRIBUTE, new Principal(caller, client, jwt.getExpiresAt()));
        MDC.put("subjectId", caller.subjectId().toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("subjectId");
        }
    }

    private boolean originAllowed(String origin, HttpServletRequest request) {
        String normalized = origin.toLowerCase(Locale.ROOT);
        if (settings.allowedOrigins().stream().anyMatch(o -> o.equalsIgnoreCase(origin))) {
            return true;
        }
        URI resource = URI.create(McpResource.uri(settings, request));
        return normalized.equals((resource.getScheme() + "://" + resource.getRawAuthority()).toLowerCase(Locale.ROOT));
    }

    private void challenge(HttpServletRequest request, HttpServletResponse response, String error, String description)
            throws IOException {
        StringBuilder value = new StringBuilder("Bearer resource_metadata=\"")
                .append(McpResource.metadataUri(settings, request)).append('"');
        if (!settings.scopes().isEmpty()) {
            value.append(", scope=\"").append(String.join(" ", settings.scopes())).append('"');
        }
        if (error != null) {
            value.append(", error=\"").append(error).append('"');
            value.append(", error_description=\"").append(description.replace("\"", "'")).append('"');
        }
        write(response, 401, value.toString(), "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32001,"
                + "\"message\":\"Authentication required.\"}}");
    }

    private static void write(HttpServletResponse response, int status, String challenge, String body) throws IOException {
        response.setStatus(status);
        if (challenge != null) {
            response.setHeader("WWW-Authenticate", challenge);
        }
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}
