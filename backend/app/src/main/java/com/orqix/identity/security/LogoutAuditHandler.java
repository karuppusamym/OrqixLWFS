package com.orqix.identity.security;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.CallerResolver;

/** Audits {@code auth.logout} before the session is invalidated. */
@Component
public class LogoutAuditHandler implements LogoutHandler {

    private final AuditWriter audit;

    public LogoutAuditHandler(AuditWriter audit) {
        this.audit = audit;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(CallerResolver.SESSION_ATTRIBUTE) instanceof Caller caller) {
            audit.write(new AuditWriter.Entry(null, AuditWriter.ActorType.USER, caller.subjectId(), "auth.logout",
                    "subject", caller.subjectId().toString(), AuditWriter.Outcome.SUCCESS, Map.of("method", "oidc")));
        }
    }
}
