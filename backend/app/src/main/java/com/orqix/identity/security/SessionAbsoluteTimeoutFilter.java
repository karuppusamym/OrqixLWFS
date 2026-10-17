package com.orqix.identity.security;

import java.io.IOException;
import java.time.Clock;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.problem.ProblemWriter;

/**
 * Absolute session lifetime (S6 section 1, default 10 h) on top of the 30 min idle timeout: a session older than the
 * limit is invalidated; API calls then get 401 and the SPA starts a new login. Created by the security
 * configuration only (not a servlet-container filter).
 */
public class SessionAbsoluteTimeoutFilter extends OncePerRequestFilter {

    private final OrqixProperties properties;
    private final ProblemWriter problems;
    private final Clock clock;

    public SessionAbsoluteTimeoutFilter(OrqixProperties properties, ProblemWriter problems, Clock clock) {
        this.properties = properties;
        this.problems = problems;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            long ageMillis = clock.millis() - session.getCreationTime();
            if (ageMillis > properties.identity().sessionAbsoluteTimeout().toMillis()) {
                session.invalidate();
                SecurityContextHolder.clearContext();
                if (request.getRequestURI().startsWith("/api/")) {
                    problems.write(response, ProblemCode.UNAUTHENTICATED, "Your session expired. Sign in again.");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }
}
