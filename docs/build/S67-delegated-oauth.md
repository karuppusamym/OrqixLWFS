# S67 - Delegated workspace OAuth lifecycle

18 October 2026. Implements the delegated consent and encrypted refresh-token lifecycle part of IN03. Full CMMN (WM04), general application designer (UI02), native additional agent protocols/A2A (remaining IN03) and approved write-capable agent tools (IN04) remain separate, unfinished requirements in the original scope.

## Configuration and consent

Connection bindings accept `oauth2AuthorizationCode` with a mounted secret reference. HTTP, Graph/document browsing and MCP bearer transport use the server-resolved access token. An administrator with current `connection.manage` can authorize the workspace connection, inspect status or disconnect. This grants the workspace connection the chosen provider account's configured scopes; it is not a per-requester credential store.

Mount a credential JSON file through the platform secret mechanism:

```json
{
  "authorizationUrl": "https://identity.example/authorize",
  "tokenUrl": "https://identity.example/token",
  "revocationUrl": "https://identity.example/revoke",
  "clientId": "approved-confidential-client",
  "clientSecret": "provision-through-secret-management",
  "scope": "approved.read offline_access",
  "redirectUri": "https://orqix.example/w/WORKSPACE_UUID/settings/connections/CONNECTION_KEY/oauth/callback"
}
```

Only `revocationUrl` is optional. Endpoints are separately checked against the existing egress policy; redirects are refused and HTTP exchanges/responses are bounded. The authorization endpoint must not contain a query or fragment. The redirect URI must exactly match the application's external origin and the connection-specific path above; configure trusted forwarded-header handling at the deployment proxy when necessary. HTTPS is required except loopback development callback URLs. Register that exact redirect with the provider. The provider must support confidential-client Basic authentication, authorization code with S256 PKCE, bearer access tokens with integer `expires_in` of 1-86400 seconds, and refresh tokens. Configure the provider's offline-access scope; provider-specific authorization query extensions are not supported.

Consent uses a random 256-bit state and independent PKCE verifier. Only a hash of state and an encrypted verifier are persisted, bound to subject/workspace/connection/configuration, with a ten-minute expiry. Each connection's distinct redirect URI and exact configured endpoints protect against provider mix-up. The authenticated response is consumed once before exchanging its code. Identical successful responses may replay within the state lifetime; changed or uncertain responses cannot issue another token request. Permission, configuration and grant revision are checked again before credentials are installed. A disconnect or competing consent prevents a late response from reinstalling credentials.

The callback page removes code/error query parameters from the browser route, never displays provider error details, and posts the response through the existing authenticated/CSRF-protected API. Existing `no-referrer` response headers prevent query leakage during application boot. The response DTO redacts its `toString` so framework debug logging cannot print the authorization code. Access/refresh tokens never enter browser storage or connection API responses. OpenAPI imports still require explicit grant review and never provision consent automatically.

## Encryption and rotation

Mount `oauth-token-keyring` in `orqix.secrets.dir`:

```json
{
  "active": "key-2026-10",
  "keys": {
    "key-2026-10": "BASE64_OF_32_RANDOM_BYTES"
  }
}
```

Generate the key with a cryptographically secure random generator and provision it through the platform's secret manager. Never use the literal placeholder or a password. All application nodes must receive the same keyring. AES-256-GCM uses fresh 96-bit nonces and binds ciphertext to its workspace/reference/purpose. Generic connection credential reads reject the reserved keyring reference; binding management rejects selecting it. Database rows contain encrypted tokens and key IDs, never plaintext tokens or the encryption key.

To rotate encryption keys, add a new random key and change `active`, retaining older keys. Valid grants are re-encrypted with the active key on use. Retain old keys until no `oauth_grant` rows or unexpired `oauth_consent` rows need them; expired consent is removed when new consent starts. Removing a needed key fails closed. Client-secret rotation is reread on every operation and does not invalidate an unchanged client/endpoints/scope/redirect grant; other configuration changes require consent again.

Before a refresh request, the application commits a fenced `refreshing` intent. This serializes refresh acquisition across nodes. Other calls fail unavailable while that refresh is in progress; normal request recovery can retry after it finishes. A successful refresh atomically replaces both access and rotated refresh tokens. If the provider omits a replacement refresh token, the current one is retained, as permitted by OAuth. A provider failure, malformed reply or uncertain refresh clears local credentials and requires new consent. A process that died during refresh is treated the same after a 30-second recovery threshold. The old refresh token is never blindly reused after an uncertain exchange. A disconnect increments the fence before optional provider revocation, preventing an in-flight refresh from reconnecting the grant.

Disconnect erases local credentials even if decryption/provider revocation is unavailable. The API distinguishes acknowledged, unconfirmed and unconfigured remote revocation. An unconfirmed provider outcome requires removing/reviewing consent at the provider; Orqix does not claim remote revocation from a local delete. HTTP/Graph 401 invalidates local credentials without automatically resending the external operation. MCP rejection does not transparently replay or renew its call. This does not admit write-capable agent tools or infer approval from provider scopes.

Apply V86 before deploying the new application. The implementation follows [RFC 9700](https://www.rfc-editor.org/rfc/rfc9700.html), [PKCE / RFC 7636](https://www.rfc-editor.org/rfc/rfc7636.html), and the existing OAuth Basic client authentication rules. Real tenant consent, provider registration and key provisioning remain deployment acceptance.

## Verification

Focused PostgreSQL/Flowable API tests cover subject/workspace permissions, PKCE verification, encrypted persistence, code replay, changed/expired consent, refresh rotation, uncertain refresh/code refusal, configuration/client-secret/key changes, local/remote disconnect and disconnect during an in-flight refresh. Vault tests cover ciphertext tampering, workspace/purpose substitution, random nonces, key retention/removal and redacted authorization-code logging. Frontend tests cover authorization/disconnect, transient callback cleanup, successful callback handling and provider denial. Browser denial/mobile accessibility is checked separately with mock API; it does not establish real-provider browser consent. The final focused Maven `verify` passes 108 application tests with zero skips/failures, compiler coverage checks (using existing accumulated coverage data) and executable packaging: `evidence/delegated-oauth-complete-verify.log`. This includes the nested-case, worker/callback, OAuth, Graph/document, agent executor, OpenAPI review, architecture and security-matrix regressions. Frontend types and 38 focused tests across six files pass (`evidence/delegated-oauth-types-complete.log`, `evidence/delegated-oauth-final-frontend.log`). The Edge denial/mobile journey passes (`evidence/delegated-oauth-browser.log`). Full frontend lint/build, 88 contract checks and E2E types are checked separately. The earlier 64-test run is superseded by the 108-test run, which also checks binding-revision invalidation and log redaction. Full-suite results are distinguished from these focused checks in the tracker.
