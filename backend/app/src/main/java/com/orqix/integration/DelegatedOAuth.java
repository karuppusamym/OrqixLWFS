package com.orqix.integration;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;
import tools.jackson.databind.JsonNode;

/** Workspace consent with PKCE and encrypted durable refresh rotation. No provider response is logged. */
@Service
public class DelegatedOAuth {
    public record Authorization(String authorizationUrl, Instant expiresAt) {}
    public record Status(String status, Instant expiresAt, String remoteRevocation) {}
    public record Finish(String state, String code) {
        @Override public String toString() { return "OAuth consent response [redacted]"; }
    }
    private record Config(String authorizationUrl, String tokenUrl, String revocationUrl, String clientId,
            String clientSecret, String scope, String redirectUri, String hash) {}
    private record Grant(String status, String configHash, String keyId, String ciphertext, Instant expiresAt,
            Instant refreshStartedAt, int revision) {}
    private record Exchange(String verifier, int revision, boolean replay) {}
    private record Refresh(String token, int revision, String access) {}
    private record Tokens(String access, String refresh, Instant expiresAt) {}
    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final OAuthVault vault;
    private final EgressPolicy egress;
    private final ConnectionService connections;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(3)).build();

    public DelegatedOAuth(JdbcClient jdbc, Json json, Clock clock, OAuthVault vault, EgressPolicy egress,
            ConnectionService connections, PolicyService policy, AuditWriter audit, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.json=json; this.clock=clock; this.vault=vault; this.egress=egress;
        this.connections=connections; this.policy=policy; this.audit=audit;
        tx=new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Authorization begin(Caller caller, UUID ws, String key, String callbackUri) {
        var binding = authorized(caller, ws, key, "beginConnectionOAuth");
        Config config = config(binding.secretRef());
        // Distinct exact redirect URIs for each workspace/connection prevent authorization-server mix-up.
        if (!config.redirectUri().equals(callbackUri)) throw OAuthVault.unavailable();
        String state = randomValue(), verifier = randomValue(), hash = Json.sha256Hex(state);
        var sealed = vault.seal(stateContext(ws, hash), verifier);
        Instant expires = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusSeconds(600);
        tx.executeWithoutResult(ignored -> {
            authorized(caller, ws, key, "beginConnectionOAuth");
            lockBinding(ws,key,binding.revision());
            jdbc.sql("DELETE FROM oauth_consent WHERE expires_at < :now")
                    .param("now", Sql.ts(clock.instant())).update();
            jdbc.sql("""
                    INSERT INTO oauth_grant(workspace_id,secret_ref,status) VALUES(:ws,:ref,'consentRequired')
                    ON CONFLICT DO NOTHING
                    """).param("ws",ws).param("ref",binding.secretRef()).update();
            Grant grant = grant(ws,binding.secretRef(),true).orElseThrow();
            long pending = jdbc.sql("SELECT count(*) FROM oauth_consent WHERE workspace_id=:ws AND secret_ref=:ref AND status='pending'")
                    .param("ws",ws).param("ref",binding.secretRef()).query(Long.class).single();
            if (pending >= 20) throw new ApiException(ProblemCode.VALIDATION_FAILED, "Too many outstanding consent requests; wait for them to expire.");
            jdbc.sql("""
                    INSERT INTO oauth_consent(state_hash,workspace_id,connection_key,secret_ref,subject_id,config_hash,
                      grant_revision,binding_revision,key_id,verifier_ciphertext,expires_at,status)
                    VALUES(:state,:ws,:key,:ref,:actor,:hash,:revision,:bindingRevision,:kid,:cipher,:expires,'pending')
                    """).param("state",hash).param("ws",ws).param("key",key).param("ref",binding.secretRef())
                    .param("actor",caller.subjectId()).param("hash",config.hash()).param("revision",grant.revision())
                    .param("bindingRevision",binding.revision()).param("kid",sealed.keyId()).param("cipher",sealed.ciphertext()).param("expires",Sql.ts(expires)).update();
            audit(caller,ws,key,"connection.oauth.started");
        });
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha(verifier));
        return new Authorization(config.authorizationUrl()+"?response_type=code&client_id="+encode(config.clientId())
                +"&redirect_uri="+encode(config.redirectUri())+"&scope="+encode(config.scope())+"&state="+state
                +"&code_challenge_method=S256&code_challenge="+challenge,expires);
    }

    public Status finish(Caller caller, UUID ws, String key, Finish request) {
        var binding = authorized(caller,ws,key,"finishConnectionOAuth");
        if (request == null || request.state()==null || !request.state().matches("[A-Za-z0-9_-]{43}")
                || request.code()==null || request.code().isBlank() || request.code().length()>4096
                || request.code().chars().anyMatch(c -> c<32 || c==127))
            throw new ApiException(ProblemCode.VALIDATION_FAILED,"The authorization response is invalid.");
        Config config = config(binding.secretRef());
        String stateHash=Json.sha256Hex(request.state()), codeHash=Json.sha256Hex(request.code());
        Exchange exchange=tx.execute(ignored -> {
            lockBinding(ws,key,binding.revision());
            var row=jdbc.sql("""
                    SELECT config_hash,grant_revision,binding_revision,key_id,verifier_ciphertext,expires_at,status,code_hash
                    FROM oauth_consent WHERE workspace_id=:ws AND connection_key=:key AND secret_ref=:ref
                      AND state_hash=:state AND subject_id=:actor FOR UPDATE
                    """).param("ws",ws).param("key",key).param("ref",binding.secretRef()).param("state",stateHash)
                    .param("actor",caller.subjectId()).query((rs,n)->Map.<String,Object>of(
                            "hash",rs.getString("config_hash").trim(),"revision",rs.getInt("grant_revision"),"bindingRevision",rs.getInt("binding_revision"),
                            "kid",rs.getString("key_id"),"cipher",rs.getString("verifier_ciphertext"),
                            "expires",Sql.instant(rs,"expires_at"),"status",rs.getString("status"),
                            "code",rs.getString("code_hash")==null?"":rs.getString("code_hash").trim()))
                    .optional().orElseThrow(ApiException::notFound);
            if (!config.hash().equals(row.get("hash")) || (int)row.get("bindingRevision")!=binding.revision()
                    || !((Instant)row.get("expires")).isAfter(clock.instant()))
                throw new ApiException(ProblemCode.VALIDATION_FAILED,"Consent expired or configuration changed; authorize again.");
            if ("succeeded".equals(row.get("status")) && codeHash.equals(row.get("code")))
                return new Exchange(null,(int)row.get("revision"),true);
            if (!"pending".equals(row.get("status"))) throw new ApiException(ProblemCode.IDEMPOTENCY_KEY_REUSED,
                    "This consent response was already consumed. Start authorization again if its result was uncertain.");
            String verifier=vault.open(stateContext(ws,stateHash),(String)row.get("kid"),(String)row.get("cipher"));
            jdbc.sql("UPDATE oauth_consent SET status='exchanging',code_hash=:code WHERE state_hash=:state AND workspace_id=:ws")
                    .param("code",codeHash).param("state",stateHash).param("ws",ws).update();
            return new Exchange(verifier,(int)row.get("revision"),false);
        });
        if (exchange.replay()) return status(caller,ws,key);
        Tokens tokens;
        try {
            tokens=tokenRequest(config,"grant_type=authorization_code&code="+encode(request.code())
                    +"&redirect_uri="+encode(config.redirectUri())+"&code_verifier="+encode(exchange.verifier()),null);
        } catch (RuntimeException ex) {
            markConsent(ws,stateHash,"failed"); throw OAuthVault.unavailable();
        }
        return tx.execute(ignored -> {
            lockBinding(ws,key,binding.revision());
            var current=authorized(caller,ws,key,"finishConnectionOAuth");
            if (!current.secretRef().equals(binding.secretRef()) || !config(current.secretRef()).hash().equals(config.hash()))
                throw OAuthVault.unavailable();
            if (!store(ws,binding.secretRef(),config,tokens,exchange.revision())) {
                updateConsent(ws,stateHash,"failed"); throw OAuthVault.unavailable();
            }
            updateConsent(ws,stateHash,"succeeded"); audit(caller,ws,key,"connection.oauth.connected");
            return new Status("connected",tokens.expiresAt(),null);
        });
    }

    public Status status(Caller caller, UUID ws, String key) {
        var binding=authorized(caller,ws,key,"getConnectionOAuth");
        return grant(ws,binding.secretRef(),false).map(g -> new Status(g.status(),g.expiresAt(),null))
                .orElse(new Status("consentRequired",null,null));
    }

    /** Local disconnect fences in-flight exchanges/refreshes before attempting optional provider revocation. */
    public Status disconnect(Caller caller, UUID ws, String key) {
        var binding=authorized(caller,ws,key,"disconnectConnectionOAuth");
        String refresh=tx.execute(ignored -> {
            authorized(caller,ws,key,"disconnectConnectionOAuth");
            lockBinding(ws,key,binding.revision());
            String token=null;
            var existing=grant(ws,binding.secretRef(),true);
            if(existing.isPresent() && existing.get().ciphertext()!=null) {
                try { token=decrypt(ws,binding.secretRef(),existing.get()).path("refresh").asString(null); }
                catch(RuntimeException unavailable) { /* Local disconnect must still erase an unreadable grant. */ }
            }
            jdbc.sql("""
                    INSERT INTO oauth_grant(workspace_id,secret_ref,status,revision) VALUES(:ws,:ref,'disconnected',1)
                    ON CONFLICT(workspace_id,secret_ref) DO UPDATE SET status='disconnected',ciphertext=NULL,key_id=NULL,
                      expires_at=NULL,refresh_started_at=NULL,revision=oauth_grant.revision+1
                    """).param("ws",ws).param("ref",binding.secretRef()).update();
            jdbc.sql("UPDATE oauth_consent SET status='failed' WHERE workspace_id=:ws AND secret_ref=:ref AND status IN ('pending','exchanging')")
                    .param("ws",ws).param("ref",binding.secretRef()).update();
            audit(caller,ws,key,"connection.oauth.disconnected"); return token;
        });
        String revocation="notConfigured";
        try {
            Config config=config(binding.secretRef());
            if (config.revocationUrl()!=null) {
                revocation=refresh!=null && send(config,config.revocationUrl(),"token="+encode(refresh)+"&token_type_hint=refresh_token").statusCode()==200
                        ? "confirmed" : "unconfirmed";
            }
        } catch (RuntimeException ex) { revocation="unconfirmed"; }
        return new Status("disconnected",null,revocation);
    }

    /** Called only by server-side credential resolution; refresh intent commits before any provider request. */
    public Optional<String> resolve(UUID ws, String ref) {
        try {
            Config config=config(ref);
            Refresh next=tx.execute(ignored -> {
                var found=grant(ws,ref,true);
                if (found.isEmpty()) return null;
                Grant grant=found.get();
                if (!config.hash().equals(grant.configHash()) || !"connected".equals(grant.status())) {
                    if ("refreshing".equals(grant.status()) && grant.refreshStartedAt().plusSeconds(30).isBefore(clock.instant()))
                        invalidateInTransaction(ws,ref,grant.revision());
                    return null;
                }
                JsonNode token=decrypt(ws,ref,grant);
                if (grant.expiresAt().isAfter(clock.instant().plusSeconds(30))) {
                    if (!grant.keyId().equals(vault.activeKeyId())) {
                        var sealed=vault.seal(grantContext(ws,ref),json.write(token));
                        jdbc.sql("UPDATE oauth_grant SET key_id=:kid,ciphertext=:cipher,revision=revision+1 WHERE workspace_id=:ws AND secret_ref=:ref")
                                .param("kid",sealed.keyId()).param("cipher",sealed.ciphertext()).param("ws",ws).param("ref",ref).update();
                    }
                    return new Refresh(null,grant.revision(),token.path("access").asString());
                }
                jdbc.sql("UPDATE oauth_grant SET status='refreshing',refresh_started_at=:now,revision=revision+1 WHERE workspace_id=:ws AND secret_ref=:ref")
                        .param("now",Sql.ts(clock.instant())).param("ws",ws).param("ref",ref).update();
                return new Refresh(token.path("refresh").asString(),grant.revision()+1,null);
            });
            if (next==null) return Optional.empty();
            if (next.access()!=null) return Optional.of(next.access());
            try {
                Tokens tokens=tokenRequest(config,"grant_type=refresh_token&refresh_token="+encode(next.token()),next.token());
                boolean saved=Boolean.TRUE.equals(tx.execute(ignored -> store(ws,ref,config,tokens,next.revision())));
                return saved ? Optional.of(tokens.access()) : Optional.empty();
            } catch (RuntimeException ex) {
                tx.executeWithoutResult(ignored -> invalidateInTransaction(ws,ref,next.revision()));
                return Optional.empty();
            }
        } catch (RuntimeException ex) { return Optional.empty(); }
    }

    /** A rejected access token requires consent; never transparently repeat an external write or a refresh token. */
    public void invalidate(UUID ws,String ref) {
        tx.executeWithoutResult(ignored -> grant(ws,ref,true).ifPresent(g -> invalidateInTransaction(ws,ref,g.revision())));
    }

    private void invalidateInTransaction(UUID ws,String ref,int revision) {
        jdbc.sql("""
                UPDATE oauth_grant SET status='consentRequired',key_id=NULL,ciphertext=NULL,expires_at=NULL,
                  refresh_started_at=NULL,revision=revision+1 WHERE workspace_id=:ws AND secret_ref=:ref AND revision=:revision
                """).param("ws",ws).param("ref",ref).param("revision",revision).update();
    }

    private boolean store(UUID ws,String ref,Config config,Tokens tokens,int revision) {
        // A mounted client/endpoint/scope change during I/O cannot bind a token to a different configuration.
        if (!config(ref).hash().equals(config.hash())) return false;
        var sealed=vault.seal(grantContext(ws,ref),json.write(json.object().put("access",tokens.access()).put("refresh",tokens.refresh())));
        return jdbc.sql("""
                UPDATE oauth_grant SET status='connected',config_hash=:hash,key_id=:kid,ciphertext=:cipher,expires_at=:expires,
                  refresh_started_at=NULL,revision=revision+1 WHERE workspace_id=:ws AND secret_ref=:ref AND revision=:revision
                """).param("hash",config.hash()).param("kid",sealed.keyId()).param("cipher",sealed.ciphertext())
                .param("expires",Sql.ts(tokens.expiresAt())).param("ws",ws).param("ref",ref).param("revision",revision).update()==1;
    }

    private Optional<Grant> grant(UUID ws,String ref,boolean lock) {
        return jdbc.sql("""
                SELECT status,config_hash,key_id,ciphertext,expires_at,refresh_started_at,revision FROM oauth_grant
                WHERE workspace_id=:ws AND secret_ref=:ref
                """+(lock?" FOR UPDATE":"")).param("ws",ws).param("ref",ref).query((rs,n)->new Grant(rs.getString("status"),
                        rs.getString("config_hash")==null?null:rs.getString("config_hash").trim(),rs.getString("key_id"),
                        rs.getString("ciphertext"),Sql.instant(rs,"expires_at"),Sql.instant(rs,"refresh_started_at"),rs.getInt("revision"))).optional();
    }

    private JsonNode decrypt(UUID ws,String ref,Grant grant) { return json.parse(vault.open(grantContext(ws,ref),grant.keyId(),grant.ciphertext())); }
    private void markConsent(UUID ws,String state,String status) {
        tx.executeWithoutResult(ignored -> updateConsent(ws,state,status));
    }
    private void updateConsent(UUID ws,String state,String status) {
        jdbc.sql("UPDATE oauth_consent SET status=:status WHERE workspace_id=:ws AND state_hash=:state")
                .param("status",status).param("ws",ws).param("state",state).update();
    }
    private void lockBinding(UUID ws,String key,int expectedRevision) {
        int revision=jdbc.sql("SELECT revision FROM connection_binding WHERE workspace_id=:ws AND connection_key=:key FOR UPDATE")
                .param("ws",ws).param("key",key).query(Integer.class).optional().orElseThrow(ApiException::notFound);
        if(revision!=expectedRevision) throw new ApiException(ProblemCode.STALE_REVISION,"The connection changed; reload it before authorizing or disconnecting.");
    }
    private ConnectionService.Binding authorized(Caller caller,UUID ws,String key,String operation) {
        var access=policy.requireVisible(caller,ws); policy.require(access,Permission.CONNECTION_MANAGE,operation,true);
        var binding=connections.binding(ws,key).orElseThrow(ApiException::notFound);
        if (!"oauth2AuthorizationCode".equals(binding.authType())) throw new ApiException(ProblemCode.VALIDATION_FAILED,"Choose delegated OAuth for this connection first.");
        return binding;
    }
    private void audit(Caller caller,UUID ws,String key,String action) {
        audit.write(new AuditWriter.Entry(ws,caller.machine()?AuditWriter.ActorType.SERVICE:AuditWriter.ActorType.USER,
                caller.subjectId(),action,"connection",key,AuditWriter.Outcome.SUCCESS,Map.of("key",key)));
    }

    private Config config(String ref) {
        try {
            var node=json.parse(vault.readSecret(ref).orElseThrow(OAuthVault::unavailable));
            if (!node.isObject() || !Set.of("authorizationUrl","tokenUrl","revocationUrl","clientId","clientSecret","scope","redirectUri")
                    .containsAll(node.propertyNames())) throw OAuthVault.unavailable();
            for(String field:Set.of("authorizationUrl","tokenUrl","clientId","clientSecret","scope","redirectUri"))
                if(!node.path(field).isString() || node.path(field).asString().isBlank()) throw OAuthVault.unavailable();
            String authorization=node.path("authorizationUrl").asString(),token=node.path("tokenUrl").asString();
            egress.check(authorization); egress.check(token);
            if (URI.create(authorization).getRawQuery()!=null || URI.create(authorization).getRawFragment()!=null) throw OAuthVault.unavailable();
            String revoke=node.has("revocationUrl")?node.path("revocationUrl").asString():null;
            if(revoke!=null) egress.check(revoke);
            if(!node.path("scope").asString().matches("[\\x21\\x23-\\x5B\\x5D-\\x7E]+(?: [\\x21\\x23-\\x5B\\x5D-\\x7E]+)*")) throw OAuthVault.unavailable();
            var redirect=URI.create(node.path("redirectUri").asString());
            if(redirect.getHost()==null || redirect.getUserInfo()!=null || redirect.getRawQuery()!=null || redirect.getRawFragment()!=null
                    || !("https".equals(redirect.getScheme()) || ("http".equals(redirect.getScheme()) && Set.of("localhost","127.0.0.1").contains(redirect.getHost()))))
                throw OAuthVault.unavailable();
            var fingerprint=(tools.jackson.databind.node.ObjectNode)node.deepCopy(); fingerprint.remove("clientSecret");
            return new Config(authorization,token,revoke,node.path("clientId").asString(),node.path("clientSecret").asString(),
                    node.path("scope").asString(),redirect.toString(),json.canonicalSha256(fingerprint));
        } catch(Exception ex) { throw OAuthVault.unavailable(); }
    }

    private Tokens tokenRequest(Config config,String body,String previousRefresh) {
        Instant started=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); var response=send(config,config.tokenUrl(),body);
        if(response.statusCode()!=200) throw OAuthVault.unavailable();
        var result=json.parse(new String(response.body(),StandardCharsets.UTF_8));
        String access=result.path("access_token").asString("");
        String refresh=result.has("refresh_token")?result.path("refresh_token").asString(""):previousRefresh;
        var lifetime=result.path("expires_in");
        if(!"Bearer".equalsIgnoreCase(result.path("token_type").asString()) || !access.matches("[A-Za-z0-9._~+/-]+=*")
                || access.length()>8192 || refresh==null || !refresh.matches("[\\x21-\\x7E]{1,8192}")
                || !lifetime.isIntegralNumber() || !lifetime.canConvertToLong() || lifetime.asLong()<1 || lifetime.asLong()>86400)
            throw OAuthVault.unavailable();
        if(result.has("scope")) {
            Set<String> requested=Set.copyOf(java.util.List.of(config.scope().split(" ")));
            if(!result.path("scope").isString() || !requested.containsAll(java.util.List.of(result.path("scope").asString().split(" "))))
                throw OAuthVault.unavailable();
        }
        Instant expires=started.plusSeconds(lifetime.asLong());
        if(!expires.isAfter(clock.instant())) throw OAuthVault.unavailable();
        return new Tokens(access,refresh,expires);
    }

    private java.net.http.HttpResponse<byte[]> send(Config config,String url,String body) {
        try {
            egress.check(url);
            String basic=Base64.getEncoder().encodeToString((encode(config.clientId())+":"+encode(config.clientSecret())).getBytes(StandardCharsets.UTF_8));
            var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).header("Accept","application/json")
                    .header("Content-Type","application/x-www-form-urlencoded").header("Authorization","Basic "+basic)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var future=http.sendAsync(request,info -> new HttpExecutor.LimitedSubscriber(24576));
            try { return future.get(6,TimeUnit.SECONDS); } finally { if(!future.isDone()) future.cancel(true); }
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw OAuthVault.unavailable(); }
        catch(Exception ex) { throw OAuthVault.unavailable(); }
    }

    private String randomValue() { byte[] bytes=new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static byte[] sha(String value) { try { return java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)); } catch(Exception ex) { throw new IllegalStateException(ex); } }
    private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    private static String grantContext(UUID ws,String ref) { return "orqix:oauth:grant:"+ws+":"+ref; }
    private static String stateContext(UUID ws,String state) { return "orqix:oauth:state:"+ws+":"+state; }
}
