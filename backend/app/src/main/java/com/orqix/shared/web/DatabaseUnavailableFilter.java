package com.orqix.shared.web;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.orqix.shared.problem.GlobalExceptionHandler;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.problem.ProblemWriter;

/**
 * Database outage outside Spring MVC (S5 section 12, T-RES-04): the JDBC session is loaded by the servlet filters
 * (session repository, session-age check, CSRF) before any controller runs, so a failing connection there never
 * reaches {@code GlobalExceptionHandler}. Without this filter the container answered such requests with an HTML
 * {@code 500} page after repeating the failing session lookup in the error dispatch (three connection timeouts,
 * 90 s). Registered ahead of the session repository filter, it turns a database failure into
 * {@code 503 DEPENDENCY_UNAVAILABLE} problem JSON with {@code Retry-After}; nothing was committed by the failed request.
 * Exceptions that are not database availability problems pass through unchanged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class DatabaseUnavailableFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DatabaseUnavailableFilter.class);

    private final ProblemWriter problems;

    public DatabaseUnavailableFilter(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | RuntimeException e) {
            if (!GlobalExceptionHandler.isDatabaseUnavailable(e)) {
                throw e;
            }
            log.warn("Dependency unavailable: {}", e.getClass().getSimpleName());
            problems.write(response, ProblemCode.DEPENDENCY_UNAVAILABLE, null, Map.of(),
                    Map.of(HttpHeaders.RETRY_AFTER, "5"));
        }
    }
}
