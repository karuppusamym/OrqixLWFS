package com.orqix.identity.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.util.UrlUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.orqix.shared.config.OrqixProperties;

/**
 * RP-initiated logout (S6 section 1): after the local session is invalidated, the browser is sent to the IdP's
 * {@code end_session_endpoint} with {@code id_token_hint} and {@code post_logout_redirect_uri = {baseUrl}/}, so the
 * IdP session ends too. The endpoint comes from provider metadata (issuer discovery) or, when the registration uses
 * explicit endpoints, from {@code orqix.identity.end-session-uri}. Without either, logout stays local.
 */
@Component
public class OidcLogoutSuccessHandler implements LogoutSuccessHandler {

    private final ClientRegistrationRepository registrations;
    private final OrqixProperties properties;

    public OidcLogoutSuccessHandler(ClientRegistrationRepository registrations, OrqixProperties properties) {
        this.registrations = registrations;
        this.properties = properties;
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        String baseUrl = UriComponentsBuilder.fromUriString(UrlUtils.buildFullRequestUrl(request))
                .replacePath(request.getContextPath()).replaceQuery(null).fragment(null).build().toUriString();
        String postLogout = baseUrl + "/";
        String target = endSessionTarget(authentication, postLogout);
        response.sendRedirect(target != null ? target : request.getContextPath() + "/");
    }

    String endSessionTarget(Authentication authentication, String postLogoutRedirect) {
        if (!(authentication instanceof OAuth2AuthenticationToken token)
                || !(token.getPrincipal() instanceof OidcUser user)) {
            return null;
        }
        String endpoint = null;
        String clientId = null;
        ClientRegistration registration = safeFind(token.getAuthorizedClientRegistrationId());
        if (registration != null) {
            clientId = registration.getClientId();
            Object metadata = registration.getProviderDetails().getConfigurationMetadata().get("end_session_endpoint");
            if (metadata != null && !metadata.toString().isBlank()) {
                endpoint = metadata.toString();
            }
        }
        if (endpoint == null) {
            endpoint = properties.identity().endSessionUri();
        }
        if (endpoint == null) {
            return null;
        }
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(endpoint)
                .queryParam("post_logout_redirect_uri", postLogoutRedirect);
        if (user.getIdToken() != null && user.getIdToken().getTokenValue() != null) {
            builder.queryParam("id_token_hint", user.getIdToken().getTokenValue());
        }
        if (clientId != null) {
            builder.queryParam("client_id", clientId);
        }
        return builder.encode().build().toUriString();
    }

    private ClientRegistration safeFind(String registrationId) {
        try {
            return registrationId == null ? null : registrations.findByRegistrationId(registrationId);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
