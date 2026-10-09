package com.orqix.identity;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

/**
 * Resolves the {@link Caller} of the current request. Browser sessions carry the caller snapshot taken at login
 * (subject upserted, IdP groups bounded to 200 values); bearer tokens map (issuer, sub) to a subject row.
 */
@Component
public class CallerResolver {

    public static final String SESSION_ATTRIBUTE = "orqix.caller";
    static final int MAX_GROUPS = 200;
    static final int MAX_GROUP_LENGTH = 256;

    private final SubjectService subjects;
    private final OrqixProperties properties;
    private final Json json;

    public CallerResolver(SubjectService subjects, OrqixProperties properties, Json json) {
        this.subjects = subjects;
        this.properties = properties;
        this.json = json;
    }

    /** @throws ApiException 401 UNAUTHENTICATED when there is no authenticated principal */
    public Caller current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ApiException(ProblemCode.UNAUTHENTICATED);
        }
        Caller caller;
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            caller = fromJwt(jwtAuthentication.getToken());
        } else if (authentication.getPrincipal() instanceof OAuth2User user) {
            caller = fromSession(user);
        } else {
            throw new ApiException(ProblemCode.UNAUTHENTICATED);
        }
        MDC.put("subjectId", caller.subjectId().toString());
        return caller;
    }

    /** Builds the caller from an OIDC login, upserting the subject (S6 section 1 steps 1-2). */
    public Caller fromLogin(OAuth2User user, boolean login) {
        String issuer = issuer(user);
        String sub = user instanceof OidcUser oidc ? oidc.getSubject() : user.getName();
        String name = firstNonBlank(attribute(user, "name"), attribute(user, "preferred_username"), sub);
        String email = attribute(user, "email");
        SubjectService.Subject subject = subjects.upsert(issuer, sub, name, email, login);
        if (subject.disabled()) {
            throw new ApiException(ProblemCode.FORBIDDEN, "This account is disabled.");
        }
        List<String> groups = groups(user);
        subjects.recordGroups(subject.id(), groups, json.write(groups));
        return new Caller(subject.id(), subject.displayName(), subject.email(), issuer, sub, groups, false);
    }

    private Caller fromSession(OAuth2User user) {
        HttpSession session = currentSession();
        if (session != null && session.getAttribute(SESSION_ATTRIBUTE) instanceof Caller cached
                && cached.externalSubject().equals(user instanceof OidcUser oidc ? oidc.getSubject() : user.getName())) {
            return cached;
        }
        Caller caller = fromLogin(user, false);
        if (session != null) {
            session.setAttribute(SESSION_ATTRIBUTE, caller);
        }
        return caller;
    }

    private Caller fromJwt(Jwt jwt) {
        String issuer = jwt.getIssuer() == null ? "urn:orqix:unknown-issuer" : jwt.getIssuer().toString();
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new ApiException(ProblemCode.UNAUTHENTICATED);
        }
        String clientId = firstNonBlank(jwt.getClaimAsString("azp"), jwt.getClaimAsString("client_id"), sub);
        SubjectService.Subject subject = subjects.find(issuer, sub)
                .orElseGet(() -> subjects.upsert(issuer, sub, clientId, null, false));
        if (subject.disabled()) {
            throw new ApiException(ProblemCode.FORBIDDEN, "This client is disabled.");
        }
        return new Caller(subject.id(), subject.displayName(), null, issuer, sub, List.of(), true);
    }

    private List<String> groups(OAuth2User user) {
        Object claim = user.getAttributes().get(properties.identity().groupsClaim());
        List<String> groups = new ArrayList<>();
        if (claim instanceof Collection<?> values) {
            for (Object value : values) {
                add(groups, value);
            }
        } else if (claim instanceof String value) {
            add(groups, value);
        }
        return groups;
    }

    private static void add(List<String> groups, Object value) {
        if (value == null || groups.size() >= MAX_GROUPS) {
            return;
        }
        String text = value.toString().trim();
        // Keycloak may emit full paths ("/group"); the dev realm disables them, but accept both forms.
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        if (!text.isEmpty() && text.length() <= MAX_GROUP_LENGTH && !groups.contains(text)) {
            groups.add(text);
        }
    }

    private static String issuer(OAuth2User user) {
        Object iss = user.getAttributes().get("iss");
        if (user instanceof OidcUser oidc && oidc.getIssuer() != null) {
            URL url = oidc.getIssuer();
            return url.toString();
        }
        return iss == null ? "urn:orqix:unknown-issuer" : iss.toString();
    }

    private static String attribute(OAuth2User user, String name) {
        Map<String, Object> attributes = user.getAttributes();
        Object value = attributes.get(name);
        return value == null ? null : value.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static HttpSession currentSession() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getSession(true);
        }
        return null;
    }
}
