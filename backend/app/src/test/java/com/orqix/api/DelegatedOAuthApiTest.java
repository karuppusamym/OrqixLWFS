package com.orqix.api;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.orqix.integration.DelegatedOAuth;
import com.orqix.integration.internal.SecretStore;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.support.Fixtures;
import com.orqix.support.IntegrationTestBase;
import com.orqix.support.Stub;
import tools.jackson.databind.node.ObjectNode;

class DelegatedOAuthApiTest extends IntegrationTestBase {
    private static final com.github.tomakehurst.wiremock.WireMockServer PROVIDER = com.orqix.support.OAuthProviderStub.server();
    @Autowired OrqixProperties properties;
    @Autowired JdbcClient jdbc;
    @Autowired SecretStore secrets;
    @Autowired DelegatedOAuth oauth;
    private String key,ref,path,base;
    private Path keyring,configFile;
    private byte[] previousKeyring;

    @BeforeEach void configure() throws Exception {
        configureFor("localhost",PROVIDER.port());
        String suffix=UUID.randomUUID().toString().substring(0,8);
        key="delegated-"+suffix; ref=key; path="/"+key; base="/workspaces/"+SANDBOX+"/connections/"+key;
        var definition=(ObjectNode)Fixtures.procurementConnection(); definition.put("key",key).put("version",1);
        assertThat(api("wade.admin").post(Map.of("definition",definition),base+"/versions").status()).isEqualTo(201);
        assertThat(api("wade.admin").put(Map.of("baseUrl",Stub.baseUrl(),"enabled",true,
                "auth",Map.of("type","oauth2AuthorizationCode","secretRef",ref)),null,base+"/binding").status()).isEqualTo(200);
        var directory=properties.secrets().dir(); Files.createDirectories(directory);
        keyring=directory.resolve("oauth-token-keyring"); previousKeyring=Files.exists(keyring)?Files.readAllBytes(keyring):null;
        Files.writeString(keyring,json.write(json.object().put("active","test").set("keys",
                json.object().put("test",Base64.getEncoder().encodeToString(new byte[32])))));
        configFile=directory.resolve(ref);
        Files.writeString(configFile,json.write(json.object().put("authorizationUrl",PROVIDER.baseUrl()+path+"/authorize")
                .put("tokenUrl",PROVIDER.baseUrl()+path+"/token").put("revocationUrl",PROVIDER.baseUrl()+path+"/revoke")
                .put("clientId","approved-client").put("clientSecret","private-client-secret").put("scope","read offline_access")
                .put("redirectUri","http://localhost/w/"+SANDBOX+"/settings/connections/"+key+"/oauth/callback")));
        token("access-first","refresh-first");
        stubFor(post(urlEqualTo(path+"/revoke")).willReturn(ok()));
    }
    @AfterEach void cleanup() throws Exception {
        if(configFile!=null) Files.deleteIfExists(configFile);
        if(keyring!=null) { if(previousKeyring==null) Files.deleteIfExists(keyring); else Files.write(keyring,previousKeyring); }
    }
    private void token(String access,String refresh) {
        stubFor(post(urlEqualTo(path+"/token")).willReturn(okJson(json.write(json.object().put("token_type","Bearer")
                .put("access_token",access).put("refresh_token",refresh).put("expires_in",3600)))));
    }
    private Map<String,String> parameters(String query) {
        var out=new java.util.HashMap<String,String>();
        for(String field:query.split("&")) { var pair=field.split("=",2);out.put(pair[0],URLDecoder.decode(pair[1],StandardCharsets.UTF_8)); }
        return out;
    }
    private String begin() throws Exception {
        var response=api("wade.admin").post(Map.of(),base+"/oauth/authorize");
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        return response.body().path("authorizationUrl").asString();
    }
    private void connect() throws Exception {
        String state=parameters(URI.create(begin()).getRawQuery()).get("state");
        var connected=api("wade.admin").post(Map.of("state",state,"code","provider-code"),base+"/oauth/complete");
        assertThat(connected.status()).as(connected.toString()).isEqualTo(200);
    }
    private void expire() {
        jdbc.sql("UPDATE oauth_grant SET expires_at=now()-interval '1 minute' WHERE workspace_id=:ws AND secret_ref=:ref")
                .param("ws",SANDBOX).param("ref",ref).update();
    }

    @Test void consentUsesPkceAndEncryptsTokensAndReplaysWithoutRepeatingTheCodeExchange() throws Exception {
        assertThat(secrets.read("oauth-token-keyring")).as("platform keys cannot be used as connection credentials").isEmpty();
        var query=parameters(URI.create(begin()).getRawQuery());
        assertThat(query.get("code_challenge_method")).isEqualTo("S256");
        assertThat(query.get("redirect_uri")).isEqualTo("http://localhost/w/"+SANDBOX+"/settings/connections/"+key+"/oauth/callback");
        String state=query.get("state"); var body=Map.of("state",state,"code","one-use-code");
        assertThat(api("dana.designer").post(body,base+"/oauth/complete").status()).isEqualTo(403);
        assertThat(api("another.admin",List.of("orqix-purchasing-admins")).post(body,base+"/oauth/complete").status()).isEqualTo(404);
        assertThat(api("wade.admin").post(body,base+"/oauth/complete").status()).isEqualTo(200);
        assertThat(api("wade.admin").post(body,base+"/oauth/complete").status()).isEqualTo(200);
        assertThat(api("wade.admin").post(Map.of("state",state,"code","different"),base+"/oauth/complete").status()).isEqualTo(409);
        var requests=findAll(postRequestedFor(urlEqualTo(path+"/token")));
        assertThat(requests).hasSize(1);
        var sent=parameters(requests.getFirst().getBodyAsString());
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                .digest(sent.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
        assertThat(challenge).isEqualTo(query.get("code_challenge"));
        assertThat(sent.get("grant_type")).isEqualTo("authorization_code");
        assertThat(secrets.credential("oauth2AuthorizationCode",ref,SANDBOX)).contains("access-first");
        assertThat(secrets.credential("oauth2AuthorizationCode",ref,PURCHASING)).isEmpty();
        assertThat(jdbc.sql("SELECT ciphertext FROM oauth_grant WHERE workspace_id=:ws AND secret_ref=:ref")
                .param("ws",SANDBOX).param("ref",ref).query(String.class).single()).doesNotContain("access-first","refresh-first");
        assertThat(api("wade.admin").get(base+"/oauth").body().toString()).doesNotContain("access-first","refresh-first","private-client-secret");
    }

    @Test void rotatesRefreshTokensDurablyAndNeverReusesARejectedRefreshToken() throws Exception {
        connect(); expire(); token("access-next","refresh-next");
        assertThat(oauth.resolve(SANDBOX,ref)).contains("access-next");
        verify(1,postRequestedFor(urlEqualTo(path+"/token")).withRequestBody(containing("refresh_token=refresh-first")));
        expire(); token("access-last","refresh-last");
        assertThat(oauth.resolve(SANDBOX,ref)).contains("access-last");
        verify(1,postRequestedFor(urlEqualTo(path+"/token")).withRequestBody(containing("refresh_token=refresh-next")));
        expire(); stubFor(post(urlEqualTo(path+"/token")).willReturn(serverError()));
        assertThat(oauth.resolve(SANDBOX,ref)).isEmpty();
        assertThat(oauth.resolve(SANDBOX,ref)).isEmpty();
        verify(1,postRequestedFor(urlEqualTo(path+"/token")).withRequestBody(containing("refresh_token=refresh-last")));
        assertThat(api("wade.admin").get(base+"/oauth").body().path("status").asString()).isEqualTo("consentRequired");
    }

    @Test void disconnectErasesCredentialsRevokesRemotelyAndFencesPendingConsent() throws Exception {
        connect(); String pending=parameters(URI.create(begin()).getRawQuery()).get("state");
        var disconnected=api("wade.admin").post(Map.of(),base+"/oauth/disconnect");
        assertThat(disconnected.status()).as(disconnected.toString()).isEqualTo(200);
        assertThat(disconnected.body().path("remoteRevocation").asString()).isEqualTo("confirmed");
        verify(1,postRequestedFor(urlEqualTo(path+"/revoke")).withRequestBody(containing("token=refresh-first")));
        assertThat(oauth.resolve(SANDBOX,ref)).isEmpty();
        assertThat(api("wade.admin").post(Map.of("state",pending,"code","late-code"),base+"/oauth/complete").status()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT ciphertext IS NULL FROM oauth_grant WHERE workspace_id=:ws AND secret_ref=:ref")
                .param("ws",SANDBOX).param("ref",ref).query(Boolean.class).single()).isTrue();
    }

    @Test void disconnectPreventsAnInflightRefreshFromReconnectingTheGrant() throws Exception {
        connect(); expire();
        stubFor(post(urlEqualTo(path+"/token")).willReturn(okJson(json.write(json.object().put("token_type","Bearer")
                .put("access_token","late-access").put("refresh_token","late-refresh").put("expires_in",3600))).withFixedDelay(1200)));
        var refreshing=java.util.concurrent.CompletableFuture.supplyAsync(()->oauth.resolve(SANDBOX,ref));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(()->jdbc.sql(
                "SELECT status FROM oauth_grant WHERE workspace_id=:ws AND secret_ref=:ref")
                .param("ws",SANDBOX).param("ref",ref).query(String.class).single().equals("refreshing"));
        assertThat(api("wade.admin").post(Map.of(),base+"/oauth/disconnect").status()).isEqualTo(200);
        assertThat(refreshing.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEmpty();
        assertThat(oauth.resolve(SANDBOX,ref)).isEmpty();
        assertThat(api("wade.admin").get(base+"/oauth").body().path("status").asString()).isEqualTo("disconnected");
    }

    @Test void rotatesEncryptionKeysAndClientSecretsWithoutReusingOldKeyMaterial() throws Exception {
        connect();
        byte[] second=new byte[32]; java.util.Arrays.fill(second,(byte)9);
        var ring=json.object().put("active","next");
        ring.putObject("keys").put("test",Base64.getEncoder().encodeToString(new byte[32]))
                .put("next",Base64.getEncoder().encodeToString(second));
        Files.writeString(keyring,json.write(ring));
        assertThat(oauth.resolve(SANDBOX,ref)).contains("access-first");
        assertThat(jdbc.sql("SELECT key_id FROM oauth_grant WHERE workspace_id=:ws AND secret_ref=:ref")
                .param("ws",SANDBOX).param("ref",ref).query(String.class).single()).isEqualTo("next");
        ((ObjectNode)ring.path("keys")).remove("test"); Files.writeString(keyring,json.write(ring));
        Files.writeString(configFile,Files.readString(configFile).replace("private-client-secret","rotated-secret"));
        expire(); token("new-access","new-refresh");
        assertThat(oauth.resolve(SANDBOX,ref)).contains("new-access");
        verify(1,postRequestedFor(urlEqualTo(path+"/token")).withBasicAuth(
                new com.github.tomakehurst.wiremock.client.BasicCredentials("approved-client","rotated-secret")));
    }

    @Test void uncertainCodeExchangeCannotBeRepeatedAndRedirectChangesAreRejected() throws Exception {
        String state=parameters(URI.create(begin()).getRawQuery()).get("state");
        stubFor(post(urlEqualTo(path+"/token")).willReturn(serverError()));
        var body=Map.of("state",state,"code","uncertain-code");
        assertThat(api("wade.admin").post(body,base+"/oauth/complete").status()).isEqualTo(503);
        assertThat(api("wade.admin").post(body,base+"/oauth/complete").status()).isEqualTo(409);
        verify(1,postRequestedFor(urlEqualTo(path+"/token")));
        Files.writeString(configFile,Files.readString(configFile).replace("http://localhost/w/","https://foreign.example/w/"));
        assertThat(api("wade.admin").post(Map.of(),base+"/oauth/authorize").status()).isEqualTo(503);
    }

    @Test void aBindingEditInvalidatesConsentThatWasStartedForTheEarlierRevision() throws Exception {
        String state=parameters(URI.create(begin()).getRawQuery()).get("state");
        int revision=api("wade.admin").get(base).body().path("binding").path("revision").asInt();
        assertThat(api("wade.admin").put(Map.of("baseUrl",Stub.baseUrl(),"enabled",false,
                "auth",Map.of("type","oauth2AuthorizationCode","secretRef",ref)),"\"r"+revision+"\"",base+"/binding").status())
                .isEqualTo(200);
        assertThat(api("wade.admin").post(Map.of("state",state,"code","stale-code"),base+"/oauth/complete").status()).isEqualTo(422);
        verify(0,postRequestedFor(urlEqualTo(path+"/token")));
    }

    @Test void expiredConsentAndChangedConfigurationFailClosed() throws Exception {
        String state=parameters(URI.create(begin()).getRawQuery()).get("state");
        jdbc.sql("UPDATE oauth_consent SET expires_at=now()-interval '1 minute' WHERE workspace_id=:ws AND state_hash=:state")
                .param("ws",SANDBOX).param("state",com.orqix.shared.json.Json.sha256Hex(state)).update();
        assertThat(api("wade.admin").post(Map.of("state",state,"code","late-code"),base+"/oauth/complete").status()).isEqualTo(422);
        connect();
        Files.writeString(configFile,Files.readString(configFile).replace("approved-client","changed-client"));
        assertThat(oauth.resolve(SANDBOX,ref)).isEmpty();
        verify(1,postRequestedFor(urlEqualTo(path+"/token")));
        Files.delete(keyring);
        assertThat(api("wade.admin").post(Map.of(),base+"/oauth/authorize").status()).isEqualTo(503);
    }
}
