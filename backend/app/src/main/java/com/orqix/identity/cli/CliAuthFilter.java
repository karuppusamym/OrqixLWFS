package com.orqix.identity.cli;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import com.orqix.identity.Caller;
import com.orqix.identity.CallerResolver;
import com.orqix.shared.problem.ApiException;

/**
 * Authenticates a CLI request (S148): a verified token becomes {@link CliAuthentication} for the person (same subject,
 * groups and permissions as their browser sign-in). A token that cannot be used leaves the request unauthenticated, so
 * the chain answers 401 problem JSON. Not a servlet filter bean: it runs only inside the CLI chain.
 */
final class CliAuthFilter extends OncePerRequestFilter {

    private final CliTokens tokens;
    private final CallerResolver callers;

    CliAuthFilter(CliTokens tokens, CallerResolver callers) {
        this.tokens = tokens;
        this.callers = callers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        Jwt jwt = header == null || header.length() <= 7 ? null : tokens.verify(header.substring(7).trim());
        if (jwt != null) {
            try {
                Caller caller = callers.fromDelegatedToken(jwt);
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new CliAuthentication(caller));
                SecurityContextHolder.setContext(context);
            } catch (ApiException e) {
                // No subject or a disabled account: the request stays unauthenticated (401).
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
