package com.orqix.identity.cli;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import com.orqix.identity.CallerResolver;
import com.orqix.identity.security.ProblemSecurityHandlers;

/**
 * The command line tool's chain (CG-K01, S148), before the machine-client chain: API requests with a bearer token
 * whose audience is a CLI audience ({@link CliSettings}). Stateless (no session and no cookie, so no CSRF). The person
 * acts with their own permissions, checked by the services exactly as for a browser session; the chain also limits the
 * routes to what the tool uses (workflows and their tests, runs, tasks, package contract tests and connection tests).
 * Every other route answers 403.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class CliSecurityConfiguration {

    static final String CSP = "default-src 'none'; frame-ancestors 'none'";

    @Bean
    @Order(-2)
    SecurityFilterChain cliChain(HttpSecurity http, CliTokens tokens, CallerResolver callers,
            ProblemSecurityHandlers problems) throws Exception {
        http.securityMatcher(request -> {
                    String header = request.getHeader("Authorization");
                    return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                            && request.getRequestURI().startsWith(request.getContextPath() + "/api/")
                            && tokens.claimsCliAudience(header.substring(7).trim());
                })
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/api/v1/me").authenticated()
                        .requestMatchers("/api/v1/workspaces/*/workflows", "/api/v1/workspaces/*/workflows/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/workspaces/*/runs", "/api/v1/workspaces/*/runs/*").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/workspaces/*/runs").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/workspaces/*/tasks").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/workspaces/*/connector-packages/contract-tests").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/workspaces/*/connections", "/api/v1/workspaces/*/connections/*").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/workspaces/*/connections/*/test").authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(new CliAuthFilter(tokens, callers), AnonymousAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .csrf(c -> c.disable())
                .requestCache(c -> c.disable())
                .securityContext(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }
}
