package com.orqix.identity.security;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.CallerResolver;
import com.orqix.shared.problem.ApiException;

/**
 * After OIDC login (S6 section 1): upsert the subject, snapshot the IdP groups into the session (the session id was
 * already rotated by Spring Security's fixation protection) and audit {@code auth.login}.
 */
@Component
public class LoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private final CallerResolver callers;
    private final AuditWriter audit;

    public LoginSuccessHandler(CallerResolver callers, AuditWriter audit) {
        this.callers = callers;
        this.audit = audit;
        setDefaultTargetUrl("/");
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws ServletException, IOException {
        if (!(authentication.getPrincipal() instanceof OAuth2User user)) {
            super.onAuthenticationSuccess(request, response, authentication);
            return;
        }
        Caller caller;
        try {
            caller = callers.fromLogin(user, true);
        } catch (ApiException e) {
            SecurityContextHolder.clearContext();
            request.getSession().invalidate();
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        request.getSession().setAttribute(CallerResolver.SESSION_ATTRIBUTE, caller);
        audit.write(new AuditWriter.Entry(null, AuditWriter.ActorType.USER, caller.subjectId(), "auth.login", "subject",
                caller.subjectId().toString(), AuditWriter.Outcome.SUCCESS, Map.of("method", "oidc")));
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
