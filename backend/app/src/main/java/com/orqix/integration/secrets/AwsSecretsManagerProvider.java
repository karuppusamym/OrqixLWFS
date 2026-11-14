package com.orqix.integration.secrets;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * AWS Secrets Manager (docs/build/S132). {@code aws-sm:prod/crm#token} reads the secret {@code prod/crm} (a name or
 * an ARN) and, with a key, one field of its JSON value. The store is reached through {@link Client}; the default
 * client calls {@code GetSecretValue} with Signature V4 using environment or web-identity credentials.
 */
@Component
public class AwsSecretsManagerProvider implements SecretProvider {

    /** The store, so tests and other transports can stand in for AWS. */
    public interface Client {
        /** @return the secret string (binary secrets as UTF-8), or empty when there is no such secret */
        Optional<String> secretValue(String secretId);
    }

    private final boolean enabled;
    private final Client client;

    @Autowired
    public AwsSecretsManagerProvider(SecretProviderSettings settings, Clock clock) {
        this(settings.aws().enabled(), settings.aws().enabled()
                ? new HttpClient(settings.aws(), new SecretHttp(settings.timeout()), clock, System::getenv) : null);
    }

    AwsSecretsManagerProvider(boolean enabled, Client client) {
        this.enabled = enabled;
        this.client = client;
    }

    @Override
    public String scheme() {
        return SecretRef.AWS;
    }

    @Override
    public boolean enabled() {
        return enabled && client != null;
    }

    @Override
    public Optional<String> resolve(SecretRef ref) {
        return client.secretValue(ref.locator()).flatMap(value -> SecretHttp.select(value, ref.key()));
    }

    /** {@code GetSecretValue} over HTTPS with Signature V4. */
    static final class HttpClient implements Client {
        private static final String STORE = "AWS Secrets Manager";

        private final String region;
        private final URI endpoint;
        private final URI stsEndpoint;
        private final SecretHttp http;
        private final Clock clock;
        private final UnaryOperator<String> env;
        private volatile AwsSigV4.Credentials assumed;
        private volatile Instant assumedUntil = Instant.EPOCH;

        HttpClient(SecretProviderSettings.Aws settings, SecretHttp http, Clock clock, UnaryOperator<String> env) {
            String configured = settings.region() != null ? settings.region()
                    : env.apply("AWS_REGION") != null ? env.apply("AWS_REGION") : env.apply("AWS_DEFAULT_REGION");
            if (configured == null || !configured.matches("^[a-z0-9-]{4,30}$")) {
                throw new IllegalStateException("orqix.secrets.aws.region (or AWS_REGION) must name an AWS region.");
            }
            this.region = configured;
            this.endpoint = SecretHttp.base(settings.endpoint(), "https://secretsmanager." + region + ".amazonaws.com");
            this.stsEndpoint = SecretHttp.base(settings.stsEndpoint(), "https://sts." + region + ".amazonaws.com");
            this.http = http;
            this.clock = clock;
            this.env = env;
        }

        @Override
        public Optional<String> secretValue(String secretId) {
            byte[] body = SecretHttp.JSON.writeValueAsString(Map.of("SecretId", secretId)).getBytes(StandardCharsets.UTF_8);
            AwsSigV4.Credentials credentials = credentials();
            Instant now = clock.instant();
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("host", host(endpoint));
            headers.put("x-amz-date", AwsSigV4.AMZ_DATE.format(now));
            headers.put("x-amz-target", "secretsmanager.GetSecretValue");
            headers.put("content-type", "application/x-amz-json-1.1");
            if (credentials.sessionToken() != null) {
                headers.put("x-amz-security-token", credentials.sessionToken());
            }
            String authorization = AwsSigV4.authorization(credentials, region, "secretsmanager", "POST", "/", "",
                    headers, body, now);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint + "/"))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).header("Authorization", authorization);
            headers.forEach((k, v) -> {
                if (!"host".equals(k)) {
                    request.header(k, v);
                }
            });
            SecretHttp.Response response = http.send(STORE, request);
            if (response.ok()) {
                JsonNode json = response.json();
                if (json.hasNonNull("SecretString")) {
                    return Optional.of(json.get("SecretString").asString());
                }
                if (json.hasNonNull("SecretBinary")) {
                    return Optional.of(new String(Base64.getDecoder().decode(json.get("SecretBinary").asString()),
                            StandardCharsets.UTF_8));
                }
                return Optional.empty();
            }
            String type = response.status() == 400 ? response.json().path("__type").asString("") : "";
            if (type.endsWith("ResourceNotFoundException")) {
                return Optional.empty();
            }
            throw new SecretProviderException(STORE + " answered " + response.status()
                    + (type.isEmpty() ? "" : " " + type.substring(type.lastIndexOf('#') + 1)) + ".");
        }

        private AwsSigV4.Credentials credentials() {
            String tokenFile = env.apply("AWS_WEB_IDENTITY_TOKEN_FILE");
            String role = env.apply("AWS_ROLE_ARN");
            if (tokenFile != null && role != null) {
                if (assumed == null || clock.instant().isAfter(assumedUntil)) {
                    assumeRole(Path.of(tokenFile), role);
                }
                return assumed;
            }
            String key = env.apply("AWS_ACCESS_KEY_ID");
            String secret = env.apply("AWS_SECRET_ACCESS_KEY");
            if (key == null || secret == null) {
                throw new SecretProviderException(STORE + " has no credentials (web identity or access key) in the environment.");
            }
            return new AwsSigV4.Credentials(key, secret, env.apply("AWS_SESSION_TOKEN"));
        }

        /** EKS IAM roles for service accounts: STS AssumeRoleWithWebIdentity, which needs no signature. */
        private synchronized void assumeRole(Path tokenFile, String role) {
            String jwt;
            try {
                jwt = Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new SecretProviderException(STORE + " web identity token could not be read.");
            }
            Map<String, String> query = new LinkedHashMap<>();
            query.put("Action", "AssumeRoleWithWebIdentity");
            query.put("Version", "2011-06-15");
            query.put("RoleArn", role);
            query.put("RoleSessionName", "orqix-secrets");
            query.put("WebIdentityToken", jwt);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(stsEndpoint + "/"))
                    .header("Accept", "application/json").header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(SecretHttp.form(query)));
            SecretHttp.Response response = http.send("AWS STS", request);
            if (!response.ok()) {
                throw new SecretProviderException("AWS STS AssumeRoleWithWebIdentity answered " + response.status() + ".");
            }
            JsonNode c = response.json().path("AssumeRoleWithWebIdentityResponse")
                    .path("AssumeRoleWithWebIdentityResult").path("Credentials");
            if (!c.hasNonNull("AccessKeyId") || !c.hasNonNull("SecretAccessKey")) {
                throw new SecretProviderException("AWS STS returned no credentials.");
            }
            assumed = new AwsSigV4.Credentials(c.get("AccessKeyId").asString(), c.get("SecretAccessKey").asString(),
                    c.path("SessionToken").asString(null));
            long expiry = c.path("Expiration").asLong(clock.instant().plusSeconds(900).getEpochSecond());
            assumedUntil = Instant.ofEpochSecond(expiry).minusSeconds(300);
        }

        private static String host(URI uri) {
            return uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
        }
    }
}
