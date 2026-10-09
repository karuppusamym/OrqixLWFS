package com.orqix.identity.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.problem.ProblemWriter;

/**
 * Security failures as problem JSON (S3 sections 2 and 4): {@code 401 UNAUTHENTICATED} for {@code /api/**} (never a
 * login redirect), {@code 403 CSRF_INVALID} for a missing or wrong CSRF token, {@code 403 FORBIDDEN} otherwise (for
 * example a bearer token outside its R0 scope).
 */
@Component
public class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ProblemWriter problems;

    public ProblemSecurityHandlers(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        problems.write(response, ProblemCode.UNAUTHENTICATED, "Sign in to continue.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        if (accessDeniedException instanceof CsrfException) {
            problems.write(response, ProblemCode.CSRF_INVALID, "Reload the page and try again.");
        } else {
            problems.write(response, ProblemCode.FORBIDDEN, null);
        }
    }
}
