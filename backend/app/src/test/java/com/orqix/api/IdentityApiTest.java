package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.orqix.support.IntegrationTestBase;
import com.orqix.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** B02.1 / B02.2 acceptance: /me, 401 problem JSON, CSRF, 404 rule for non-members (T-SEC-05 part). */
class IdentityApiTest extends IntegrationTestBase {

    @Test
    void unauthenticatedApiCallIs401ProblemNotRedirect() throws Exception {
        Response response = api(null).get("/me");
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.header("Content-Type")).startsWith("application/problem+json");
        assertThat(response.code()).isEqualTo("UNAUTHENTICATED");
        assertThat(response.body().path("correlationId").asString()).isNotBlank();
    }

    @Test
    void requesterSeesPurchasingWithWorkflowStart() throws Exception {
        Response response = api("rhea.requester").get("/me");
        assertThat(response.status()).isEqualTo(200);
        JsonNode purchasing = workspace(response.body(), "purchasing");
        assertThat(purchasing).isNotNull();
        assertThat(values(purchasing.path("permissions"))).contains("workflow.start");
        assertThat(response.body().path("platformAdmin").asBoolean()).isFalse();
    }

    @Test
    void outsiderHasNoWorkspaces() throws Exception {
        Response response = api("otto.outsider").get("/me");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().path("workspaces").size()).isZero();
        assertThat(api("otto.outsider").get("/workspaces/{id}", PURCHASING).status()).isEqualTo(404);
    }

    @Test
    void platformAdminIsBootstrappedButHasNoBusinessPermissions() throws Exception {
        Response me = api("pat.platform").get("/me");
        assertThat(me.body().path("platformAdmin").asBoolean()).isTrue();
        Response workspace = api("pat.platform").get("/workspaces/{id}", PURCHASING);
        assertThat(workspace.status()).isEqualTo(200);
        assertThat(values(workspace.body().path("permissions"))).containsExactly("workspace.manage-access");
    }

    @Test
    void foreignWorkspaceIs404() throws Exception {
        assertThat(api("fred.facilities").get("/workspaces/{id}", PURCHASING).status()).isEqualTo(404);
        assertThat(api("fred.facilities").get("/workspaces/{id}", FACILITIES).status()).isEqualTo(200);
    }

    @Test
    void unsafeRequestWithoutCsrfTokenIs403CsrfInvalid() throws Exception {
        var request = MockMvcRequestBuilders.post("/api/v1/workspaces/{id}/workflows", PURCHASING)
                .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"csrf-test\",\"name\":\"Csrf\"}")
                .with(TestUsers.as("dana.designer"));
        Response response = api(null).send(request);
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("CSRF_INVALID");
    }

    @Test
    void logoutRedirectsToIdpEndSessionWithIdTokenHint() throws Exception {
        var result = mvc.perform(MockMvcRequestBuilders.post("/logout")
                        .with(TestUsers.as("rhea.requester"))
                        .with(IntegrationTestBase::xsrf))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith("https://idp.test.example/realms/orqix-test/protocol/openid-connect/logout?")
                .contains("post_logout_redirect_uri=http://localhost/")
                .contains("id_token_hint=");
        // Without CSRF the logout is refused.
        var refused = mvc.perform(MockMvcRequestBuilders.post("/logout").with(TestUsers.as("rhea.requester"))).andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void spaFormLogoutWithRawCookieTokenEndsSessionAndGoesToIdp() throws Exception {
        var first = mvc.perform(MockMvcRequestBuilders.get("/api/v1/me").with(TestUsers.as("rhea.requester"))).andReturn();
        jakarta.servlet.http.Cookie session = first.getResponse().getCookie("ORQIX_SESSION");
        // The SPA reads the XSRF-TOKEN cookie (HttpOnly=false) and echoes the raw value.
        jakarta.servlet.http.Cookie xsrf = first.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).as("XSRF-TOKEN cookie").isNotNull();
        assertThat(xsrf.isHttpOnly()).isFalse();
        assertThat(session).as("ORQIX_SESSION cookie").isNotNull();
        assertThat(session.isHttpOnly()).isTrue();
        String sessionId = new String(java.util.Base64.getDecoder().decode(session.getValue()));
        assertThat(sessions(sessionId)).isEqualTo(1);
        // Header form (fetch) with the raw cookie value works for API calls.
        var api = mvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/{ws}/notifications/{id}/read",
                        PURCHASING, java.util.UUID.randomUUID()).cookie(xsrf, session)
                .header("X-XSRF-TOKEN", xsrf.getValue()).with(TestUsers.as("rhea.requester"))).andReturn();
        assertThat(api.getResponse().getStatus()).isEqualTo(404);
        // Top-level form POST with the raw cookie value as _csrf.
        var logout = mvc.perform(MockMvcRequestBuilders.post("/logout").cookie(xsrf, session)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("_csrf", xsrf.getValue())
                .with(TestUsers.as("rhea.requester"))).andReturn();
        assertThat(logout.getResponse().getStatus()).isEqualTo(302);
        assertThat(logout.getResponse().getRedirectedUrl())
                .startsWith("https://idp.test.example/realms/orqix-test/protocol/openid-connect/logout?");
        assertThat(sessions(sessionId)).isZero();
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private int sessions(String sessionId) {
        return jdbc.sql("SELECT count(*) FROM spring_session WHERE session_id = :id").param("id", sessionId)
                .query(Integer.class).single();
    }

    @Test
    void apiCallsAreNeverSavedAsPostLoginTarget() throws Exception {
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        var response = mvc.perform(MockMvcRequestBuilders.get("/api/v1/me").session(session)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_HTML)).andReturn();
        assertThat(response.getResponse().getStatus()).isEqualTo(401);
        assertThat(session.getAttribute("SPRING_SECURITY_SAVED_REQUEST")).isNull();
        // After login, the success handler therefore lands on "/" (or a remembered page), never on /api/**.
        var cache = new org.springframework.security.web.savedrequest.HttpSessionRequestCache();
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/me");
        request.setSession(session);
        assertThat(cache.getRequest(request, new org.springframework.mock.web.MockHttpServletResponse())).isNull();
    }

    @Test
    void groupsListedForReadersWithMembersOnlyForAccessManagers() throws Exception {
        Response designer = api("dana.designer").get("/workspaces/{id}/groups", PURCHASING);
        assertThat(designer.status()).isEqualTo(200);
        assertThat(designer.body().path("items").findValues("key").stream().map(JsonNode::asString))
                .contains("managers", "finance-approvers");
        assertThat(designer.body().path("items").get(0).has("members")).isFalse();
        Response admin = api("wade.admin").get("/workspaces/{id}/groups", PURCHASING);
        assertThat(admin.body().path("items").get(0).has("members")).isTrue();
        assertThat(api("rhea.requester").get("/workspaces/{id}/groups", PURCHASING).status()).isEqualTo(403);
    }

    static JsonNode workspace(JsonNode me, String key) {
        for (JsonNode w : me.path("workspaces")) {
            if (key.equals(w.path("key").asString())) {
                return w;
            }
        }
        return null;
    }

    static List<String> values(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asString()));
        return values;
    }
}
