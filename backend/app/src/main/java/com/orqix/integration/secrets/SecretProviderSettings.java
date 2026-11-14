package com.orqix.integration.secrets;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Secret providers behind {@code secretRef} (docs/build/S132). Mounted files ({@code orqix.secrets.dir}) are always
 * on; every remote provider is off until enabled. Values here are addresses and identities, never secret values: a
 * Vault token is read from {@code token-file} (or given in {@code token} for local development only).
 */
@ConfigurationProperties("orqix.secrets")
public record SecretProviderSettings(Duration cacheTtl, Duration timeout, Vault vault, Aws aws, Azure azure, Gcp gcp) {

    public SecretProviderSettings {
        cacheTtl = cacheTtl == null ? Duration.ofSeconds(30) : cacheTtl;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        vault = vault == null ? new Vault(false, null, null, null, null, null, null, null, null) : vault;
        aws = aws == null ? new Aws(false, null, null, null) : aws;
        azure = azure == null ? new Azure(false, null, null, null, null) : azure;
        gcp = gcp == null ? new Gcp(false, null, null) : gcp;
    }

    /** Defaults only: file secrets, 30 s cache, no remote provider. */
    public static SecretProviderSettings defaults() {
        return new SecretProviderSettings(null, null, null, null, null, null);
    }

    /**
     * HashiCorp Vault, KV version 2. {@code auth} is {@code token} (a token file such as one written by Vault Agent)
     * or {@code kubernetes} (the pod's service-account token is exchanged for a Vault token at
     * {@code auth/<kubernetesMount>/login} with {@code kubernetesRole}).
     */
    public record Vault(boolean enabled, URI address, String namespace, String auth, String token, Path tokenFile,
            String kubernetesRole, String kubernetesMount, Path kubernetesJwtFile) {
        public Vault {
            address = blank(address) ? null : address;
            tokenFile = tokenFile == null || tokenFile.toString().isBlank() ? null : tokenFile;
            auth = auth == null || auth.isBlank() ? "token" : auth;
            kubernetesMount = kubernetesMount == null || kubernetesMount.isBlank() ? "kubernetes" : kubernetesMount;
            kubernetesJwtFile = kubernetesJwtFile == null
                    ? Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token") : kubernetesJwtFile;
        }
    }

    /**
     * AWS Secrets Manager. Credentials come from the environment: a web identity (EKS IRSA:
     * {@code AWS_WEB_IDENTITY_TOKEN_FILE} and {@code AWS_ROLE_ARN}) or static {@code AWS_ACCESS_KEY_ID} /
     * {@code AWS_SECRET_ACCESS_KEY} / {@code AWS_SESSION_TOKEN}. {@code endpoint} and {@code stsEndpoint} override
     * the regional endpoints (VPC endpoints, tests).
     */
    public record Aws(boolean enabled, String region, URI endpoint, URI stsEndpoint) {
        public Aws {
            region = region == null || region.isBlank() ? null : region;
            endpoint = blank(endpoint) ? null : endpoint;
            stsEndpoint = blank(stsEndpoint) ? null : stsEndpoint;
        }
    }

    /**
     * Azure Key Vault. Workload identity ({@code AZURE_FEDERATED_TOKEN_FILE}, {@code AZURE_CLIENT_ID},
     * {@code AZURE_TENANT_ID}) when present, otherwise the managed identity endpoint. {@code clientId} selects a
     * user-assigned managed identity. {@code vaultEndpoint} replaces {@code https://<vault>.vault.azure.net}
     * ({@code {vault}} is replaced by the vault name).
     */
    public record Azure(boolean enabled, String clientId, String vaultEndpoint, URI authorityHost, URI identityEndpoint) {
        public Azure {
            clientId = clientId == null || clientId.isBlank() ? null : clientId;
            vaultEndpoint = vaultEndpoint == null || vaultEndpoint.isBlank() ? null : vaultEndpoint;
            authorityHost = blank(authorityHost) ? null : authorityHost;
            identityEndpoint = blank(identityEndpoint) ? null : identityEndpoint;
        }
    }

    /**
     * Google Secret Manager with the workload identity of the pod or VM (metadata server token).
     */
    public record Gcp(boolean enabled, URI endpoint, URI metadataEndpoint) {
        public Gcp {
            endpoint = blank(endpoint) ? null : endpoint;
            metadataEndpoint = blank(metadataEndpoint) ? null : metadataEndpoint;
        }
    }

    private static boolean blank(URI uri) {
        return uri == null || uri.toString().isBlank();
    }
}
