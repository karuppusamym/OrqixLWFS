package com.orqix.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import java.util.List;
import java.util.Map;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Signs MockMvc requests in as the dev-realm users of S1 section 6.1 (same IdP group names as
 * deploy/compose/keycloak/orqix-dev-realm.json), through Spring Security's {@code oidcLogin()}.
 */
public final class TestUsers {

    public static final String ISSUER = "https://idp.test.example/realms/orqix-test";

    public static final Map<String, List<String>> GROUPS = Map.ofEntries(
            Map.entry("pat.platform", List.of("orqix-platform-admins")),
            Map.entry("wade.admin", List.of("orqix-purchasing-admins")),
            Map.entry("dana.designer", List.of("orqix-purchasing-designers")),
            Map.entry("paul.publisher", List.of("orqix-purchasing-publishers")),
            Map.entry("olga.operator", List.of("orqix-purchasing-operators")),
            Map.entry("vic.viewer", List.of("orqix-purchasing-viewers")),
            Map.entry("rhea.requester", List.of("orqix-purchasing-requesters")),
            Map.entry("mani.manager", List.of("orqix-purchasing-managers", "orqix-purchasing-requesters")),
            Map.entry("max.manager", List.of("orqix-purchasing-managers", "orqix-purchasing-requesters")),
            Map.entry("fin.finance", List.of("orqix-purchasing-finance")),
            Map.entry("wally.worker", List.of("orqix-purchasing-workers")),
            Map.entry("fred.facilities", List.of("orqix-facilities-requesters")),
            Map.entry("otto.outsider", List.of()));

    public static final List<String> ALL = List.copyOf(GROUPS.keySet());

    private TestUsers() {
    }

    public static RequestPostProcessor as(String username) {
        List<String> groups = GROUPS.get(username);
        if (groups == null) {
            throw new IllegalArgumentException("Unknown test user " + username);
        }
        return as(username, groups);
    }

    public static RequestPostProcessor as(String username, List<String> groups) {
        String[] parts = username.split("\\.");
        String name = capitalize(parts[0]) + " " + capitalize(parts.length > 1 ? parts[1] : "");
        return oidcLogin().idToken(token -> token
                .issuer(ISSUER)
                .subject("kc-" + username)
                .claim("preferred_username", username)
                .claim("name", name.trim())
                .claim("email", username + "@orqix.example")
                .claim("groups", groups));
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
