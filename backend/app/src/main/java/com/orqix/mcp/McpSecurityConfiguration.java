package com.orqix.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import com.orqix.identity.CallerResolver;

/**
 * The dedicated security chain of the MCP server (S136 section 2), before every other chain: {@code /mcp} and the
 * protected resource metadata. Stateless (no session, no cookies, so no CSRF), no saved requests, never the OIDC
 * login redirect. {@link McpAuthFilter} authenticates {@code /mcp}; the metadata is public.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class McpSecurityConfiguration {

    static final String CSP = "default-src 'none'; frame-ancestors 'none'";

    @Bean
    @Order(-1)
    SecurityFilterChain mcpChain(HttpSecurity http, McpTokens tokens, CallerResolver callers, McpSettings settings)
            throws Exception {
        http.securityMatcher(request -> {
                    String path = request.getRequestURI().substring(request.getContextPath().length());
                    return path.equals(McpResource.PATH) || path.equals(McpResource.PATH + "/")
                            || path.equals(McpResource.METADATA_PATH) || path.startsWith(McpResource.METADATA_PATH + "/");
                })
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .addFilterBefore(new McpAuthFilter(tokens, callers, settings), AnonymousAuthenticationFilter.class)
                .csrf(c -> c.disable())
                .requestCache(c -> c.disable())
                .securityContext(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }
}
