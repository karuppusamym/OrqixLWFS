package com.orqix.identity.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Session cookie (S3 section 2, S6 section 1): {@code ORQIX_SESSION}, HttpOnly, SameSite=Lax (required for the IdP
 * redirect back), Secure unless explicitly disabled for local http. Declared explicitly so the same cookie is used
 * with an embedded server, a WAR container and in tests (Boot only applies {@code server.servlet.session.cookie.*}
 * to Spring Session when it manages an embedded server).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class SessionCookieConfiguration {

    @Bean
    CookieSerializer orqixSessionCookieSerializer(
            @Value("${server.servlet.session.cookie.name:ORQIX_SESSION}") String name,
            @Value("${server.servlet.session.cookie.secure:true}") boolean secure,
            @Value("${server.servlet.session.cookie.same-site:lax}") String sameSite) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(name);
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(secure);
        serializer.setSameSite(sameSite.substring(0, 1).toUpperCase() + sameSite.substring(1).toLowerCase());
        return serializer;
    }
}
