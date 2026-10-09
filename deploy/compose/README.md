# Local development stack (Docker Compose)

PostgreSQL, Keycloak (dev realm), the procurement stub and, optionally, the Orqix app image with its migration job.
**Development only**: every credential here is a documented test value and must never be reused anywhere else.

```bash
cd deploy/compose

# Dependencies only; run the backend and the Vite dev server from the IDE / npm (profile `dev`)
docker compose up -d postgres keycloak procurement-stub

# Everything, including the app image built from the root Dockerfile (migration job runs first, then the app)
docker compose --profile app up -d --build

# Migration job only (Flyway + Flowable schema), then it exits
docker compose --profile migrate run --rm orqix-migrate

# Stop (keeps the database volume) / stop and delete the database volume
docker compose --profile app down
docker compose --profile app down -v
```

On Windows Git Bash prefix `docker` commands that carry container paths with `MSYS_NO_PATHCONV=1`.

## Services and host ports

All host ports can be overridden through environment variables (copy `.env.example` to `.env`; the file is git-ignored).
Port 8090 is deliberately not used on the host.

| Service | Host port (variable) | Inside the network | Notes |
|---|---|---|---|
| `postgres` (`postgres:18-alpine`, `POSTGRES_IMAGE`) | 5432 (`POSTGRES_HOST_PORT`) | `postgres:5432` | DB `orqix`; roles and schemas below |
| `keycloak` (`quay.io/keycloak/keycloak:26.8.0`, `KEYCLOAK_IMAGE`) | 8180 (`KEYCLOAK_HOST_PORT`) | `keycloak:8080` | `start-dev --import-realm`, realm `orqix-dev` |
| `procurement-stub` (built from `tools/procurement-stub`) | 8095 (`STUB_HOST_PORT`) | `procurement-stub:8090` | contract in `tools/procurement-stub/README.md` |
| `orqix-migrate` (profiles `migrate`, `app`) | - | - | one-shot, same image as the app, `SPRING_PROFILES_ACTIVE=migrate,dev` |
| `orqix` (profile `app`) | 8080 and 8081 (`ORQIX_HOST_PORT`, `ORQIX_MANAGEMENT_HOST_PORT`) | `orqix:8080` | waits for `orqix-migrate` (`service_completed_successfully`) |

All services have health checks; `docker compose ps` shows `healthy` when ready. Keycloak needs about 30-60 s on first start.

The app and migration containers run like OpenShift pods: arbitrary UID in group 0 (`ORQIX_UID`, default 1000770000),
read-only root filesystem, `tmpfs` at `/tmp`, all capabilities dropped, `no-new-privileges` (test T-OPS-01 on every start).

## PostgreSQL

`postgres/init/01-orqix-init.sh` + `postgres/sql/orqix-init.sql` run once on the first start of an empty data volume
(`down -v` re-runs them):

| Object | Detail |
|---|---|
| database `orqix` | owned by `orqix_migrator`; `PUBLIC` has no access |
| role `orqix_migrator` | DDL owner; used only by the migration job (Flyway for schema `orqix`, Flowable schema update for `flowable`) |
| role `orqix_app` | runtime role: `USAGE` on both schemas, no `CREATE`; DML on application tables is granted by the Flyway migrations (`${app_role}`) |
| schemas `orqix`, `flowable` | owned by `orqix_migrator` |
| default privileges | `ALTER DEFAULT PRIVILEGES FOR ROLE orqix_migrator IN SCHEMA flowable GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES` (and `USAGE, SELECT ON SEQUENCES`) `TO orqix_app` |

Passwords come from `POSTGRES_SUPERUSER_PASSWORD`, `ORQIX_MIGRATOR_PASSWORD`, `ORQIX_APP_PASSWORD` (dev defaults in
`.env.example`; the role defaults match the backend's `application-dev.yaml`). Changing them requires `down -v`.

```bash
docker compose exec postgres psql -U orqix_app -h 127.0.0.1 -d orqix      # password: ORQIX_APP_PASSWORD
```

## Keycloak realm `orqix-dev` and the issuer problem

`keycloak/orqix-dev-realm.json` is imported on start. Brute-force protection is off. Admin console:
`http://localhost:8180` (`KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`, dev defaults in `.env.example`).
Changes made in the console are lost when the container is removed; edit the realm file instead.

| Client | Type | Details |
|---|---|---|
| `orqix-web` | confidential, standard flow only | PKCE **S256 required**; redirect URIs `http://localhost:8080/login/oauth2/code/orqix` and `http://localhost:5173/login/oauth2/code/orqix`; post-logout redirects `http://localhost:8080/*`, `http://localhost:5173/*`; mapper `groups` (group membership, **full path off**) in ID token, access token and userinfo; secret `ORQIX_WEB_CLIENT_SECRET` (value in the realm file) |
| `orqix-machine-demo` | confidential, client credentials only | service account; audience mapper adds `aud: orqix-api` to the access token; no browser flow, no password grant |

**Users and groups** (S1 section 6.1). Passwords are dev-only test values defined in `keycloak/orqix-dev-realm.json`
(one per user); they are not repeated here.

| User | Groups |
|---|---|
| pat.platform | orqix-platform-admins |
| wade.admin | orqix-purchasing-admins |
| dana.designer | orqix-purchasing-designers |
| paul.publisher | orqix-purchasing-publishers |
| olga.operator | orqix-purchasing-operators |
| vic.viewer | orqix-purchasing-viewers |
| rhea.requester | orqix-purchasing-requesters |
| mani.manager, max.manager | orqix-purchasing-managers, orqix-purchasing-requesters |
| fin.finance | orqix-purchasing-finance |
| fred.facilities | orqix-facilities-requesters |
| otto.outsider | (none) |

Emails are `<username>@orqix.example`; first and last name are the two words of the username capitalised.

### Two ways to reach Keycloak

The browser uses `http://localhost:8180`; a containerised app uses `http://keycloak:8080`. Keycloak is started with
`KC_HOSTNAME=http://localhost:8180` and `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`, so the **issuer is always**
`http://localhost:8180/realms/orqix-dev` (what tokens carry), while the endpoints returned to callers on the Compose network
(token, JWKS, userinfo) point at `keycloak:8080`.

* **Backend on the host (IDE, profile `dev`):** plain issuer discovery works:
  `ORQIX_OIDC_ISSUER=http://localhost:8180/realms/orqix-dev` (the dev profile's default). Set the client secret from the realm file:
  `ORQIX_OIDC_CLIENT_SECRET`. The database defaults in `application-dev.yaml` match this stack.
* **Backend in the `app` profile (container):** issuer discovery cannot be used (`localhost` inside the container is the
  container), so the service passes explicit endpoints. Exact variables on service `orqix`:

  | Variable | Value |
  |---|---|
  | `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_ORQIX_CLIENT_ID` / `_CLIENT_SECRET` | `orqix-web` / `ORQIX_WEB_CLIENT_SECRET` |
  | `..._REGISTRATION_ORQIX_AUTHORIZATION_GRANT_TYPE`, `_CLIENT_AUTHENTICATION_METHOD`, `_SCOPE`, `_REDIRECT_URI`, `_PROVIDER` | `authorization_code`, `client_secret_basic`, `openid,profile,email`, `{baseUrl}/login/oauth2/code/{registrationId}`, `orqix` |
  | `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_ORQIX_AUTHORIZATION_URI` | `http://localhost:${KEYCLOAK_HOST_PORT}/realms/orqix-dev/protocol/openid-connect/auth` (the browser follows it) |
  | `..._PROVIDER_ORQIX_TOKEN_URI` | `http://keycloak:8080/realms/orqix-dev/protocol/openid-connect/token` |
  | `..._PROVIDER_ORQIX_JWK_SET_URI` | `http://keycloak:8080/realms/orqix-dev/protocol/openid-connect/certs` |
  | `..._PROVIDER_ORQIX_USER_INFO_URI` | `http://keycloak:8080/realms/orqix-dev/protocol/openid-connect/userinfo` |
  | `..._PROVIDER_ORQIX_USER_NAME_ATTRIBUTE` | `preferred_username` |
  | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | `http://localhost:${KEYCLOAK_HOST_PORT}/realms/orqix-dev` (validated against `iss`, never fetched) |
  | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` | `http://keycloak:8080/realms/orqix-dev/protocol/openid-connect/certs` |
  | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES` | `orqix-api` |

  Because the registration has no `issuer-uri`, Spring does not validate the ID token's `iss` for browser login; the JWT
  resource server for machine clients does validate it.

Application settings passed to the container: `ORQIX_IDENTITY_BOOTSTRAP_PLATFORM_ADMIN_GROUPS=orqix-platform-admins`,
`ORQIX_EGRESS_ALLOWED_HOSTS`, `ORQIX_EGRESS_ALLOWED_PRIVATE_HOSTS` and `ORQIX_EGRESS_PLAIN_HTTP_HOSTS` all `procurement-stub:8090`
(plain HTTP to the stub is allowed in dev only), `ORQIX_SECRETS_DIR=/var/run/secrets/orqix` (mounted from `./secrets`, empty in dev).
For a backend on the host the stub is `localhost:8095` (already the dev profile default).

Machine client example:

```bash
SECRET=$(python -c "import json;print(next(c['secret'] for c in json.load(open('keycloak/orqix-dev-realm.json'))['clients'] if c['clientId']=='orqix-machine-demo'))")
curl -s -X POST http://localhost:8180/realms/orqix-dev/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=orqix-machine-demo -d client_secret="$SECRET"
```

Browsers accept Keycloak's `Secure` cookies on `http://localhost`; non-browser test clients must be told to send them
over plain HTTP (for example Python's `DefaultCookiePolicy(secure_protocols=("http", "https"))`).

## Procurement stub in the stack

`http://localhost:8095` from the host, `http://procurement-stub:8090` from containers. Quick check and fault injection:

```bash
curl -s localhost:8095/health
curl -s -X POST localhost:8095/orders -H 'Idempotency-Key: demo-1' -d '{"title":"Laptop","amount":1800.07,"costCenter":"IT"}'
curl -s -X POST localhost:8095/__faults -d '{"mode":"status500","times":2}'   # modes: see tools/procurement-stub/README.md
curl -s -X POST localhost:8095/__reset
```

## Migration order

`orqix-migrate` (profile `migrate`, plus `dev` for the dev seed) runs as `orqix_migrator`, applies Flyway to `orqix` and
creates the Flowable tables in `flowable`, then exits 0. `orqix` starts only after it completed successfully and connects as
`orqix_app` with schema update disabled. Re-running the job on an up-to-date database is a no-op.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `port is already allocated` | another program uses the port; set the matching `*_HOST_PORT` in `.env` |
| login redirects to a URL ending in a port you did not expect | `KEYCLOAK_HOST_PORT` changed: the issuer follows it, but the registered redirect URIs for the app are fixed to 8080/5173 |
| roles or passwords have no effect after changing `.env` | PostgreSQL role setup runs only on an empty volume: `docker compose down -v` |
| `exec ... permission denied` / read-only errors in the app | the app runs with a read-only root filesystem on purpose; write only to `/tmp` |
| Keycloak unhealthy for a minute | normal on first start (realm import); `docker compose logs keycloak` |
