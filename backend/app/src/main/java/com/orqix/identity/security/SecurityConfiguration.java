package com.orqix.identity.security;

import java.io.IOException;
import java.time.Clock;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.problem.ProblemWriter;

/**
 * Three filter chains (S6 sections 1 and 6): the management port (health and Prometheus only, no authentication;
 * never routed), machine clients with a bearer JWT (restricted to startRun/getRun/listRunEvents, CSRF-exempt, no
 * session), and browser sessions (OIDC login with PKCE, JDBC session, cookie CSRF for the SPA, 401 problem JSON for
 * {@code /api/**}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class SecurityConfiguration {

    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; frame-ancestors 'none'; base-uri 'self'";

    private static final RequestMatcher BEARER = request -> {
        String header = request.getHeader("Authorization");
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7);
    };

    @Bean
    @Order(1)
    SecurityFilterChain managementChain(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(c -> c.disable())
                .requestCache(c -> c.disable())
                .securityContext(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain machineChain(HttpSecurity http, ProblemSecurityHandlers problems) throws Exception {
        http.securityMatcher(BEARER)
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/v1/workspaces/*/runs").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/workspaces/*/runs/*").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/workspaces/*/runs/*/events").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .csrf(c -> c.disable())
                .requestCache(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain browserChain(HttpSecurity http, ProblemSecurityHandlers problems,
            LoginSuccessHandler loginSuccess, ClientRegistrationRepository registrations,
            LogoutAuditHandler logoutAudit, OidcLogoutSuccessHandler logoutSuccess, OrqixProperties properties, ProblemWriter problemWriter, Clock clock)
            throws Exception {
        SessionAbsoluteTimeoutFilter absoluteTimeout = new SessionAbsoluteTimeoutFilter(properties, problemWriter, clock);
        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2Login(o -> o
                        .loginPage("/oauth2/authorization/orqix")
                        .authorizationEndpoint(e -> e.authorizationRequestResolver(resolver))
                        .successHandler(loginSuccess))
                .logout(l -> l.logoutUrl("/logout").addLogoutHandler(logoutAudit).logoutSuccessHandler(logoutSuccess))
                // Cookie-to-header CSRF for the SPA. The raw cookie value is accepted from the X-XSRF-TOKEN header or
                // the _csrf form parameter (the SPA signs out with a top-level form POST); BREACH masking adds nothing
                // for a token the page can read from a cookie anyway.
                .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .requestCache(c -> c.requestCache(browserRequestCache()))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .addFilterBefore(absoluteTimeout, CsrfFilter.class)
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(problems, request -> request.getRequestURI().startsWith("/api/"))
                        .accessDeniedHandler(problems))
                .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()))
                .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .frameOptions(f -> f.deny()));
        return http.build();
    }

    /**
     * Only top-level page navigations are remembered for the post-login redirect: never API calls (the SPA's 401 on
     * {@code /api/v1/me} must not become the landing page), non-GET requests or non-HTML requests.
     */
    static HttpSessionRequestCache browserRequestCache() {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.setRequestMatcher(request -> "GET".equals(request.getMethod())
                && !request.getRequestURI().startsWith("/api/")
                && !request.getRequestURI().startsWith("/oauth2/")
                && !request.getRequestURI().startsWith("/login")
                && !request.getRequestURI().startsWith("/logout")
                && !"XMLHttpRequest".equals(request.getHeader("X-Requested-With"))
                && request.getHeader("Accept") != null && request.getHeader("Accept").contains("text/html"));
        return cache;
    }

    /** Loads the deferred CSRF token so the {@code XSRF-TOKEN} cookie is always present for the SPA. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
