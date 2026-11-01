# Sovereign single-VM deployment

For regulated organisations that must run Orqix **in their own data centre, in their own country, on one server**, with
no dependency on a cloud service or on outbound internet. Strategy context: [Blue Ocean discovery §3, Raise "Operational
lightness and sovereignty"](27-blue-ocean-discovery.md). The kit lives in [`deploy/single-vm/`](../deploy/single-vm/README.md).

| | |
|---|---|
| What runs | Five containers on one Linux VM: `proxy` (nginx, TLS), `orqix` (UI + API + Flowable engine), `orqix-migrate` (one-shot schema job), `keycloak` (identity provider, optional), `postgres` |
| Where data lives | One Docker volume, `<project>_pgdata`, and your backup folder. Nothing else |
| Outbound connections | None by default. The app, IdP and database are on a Docker network with no route out (`internal: true`) |
| Entry points | HTTPS on one port (UI, API, sign-in pages under `/auth`); HTTP redirects to HTTPS; health and metrics on `127.0.0.1` |
| Operations | `scripts/preflight.sh`, `install.sh`, `health.sh`, `add-user.sh`, `backup.sh`, `restore.sh`, `upgrade.sh`, `save-images.sh`, `load-images.sh` |

Contents: [1 Sizing](#1-sizing) · [2 Install](#2-install-air-gapped) · [3 Identity](#3-identity) ·
[4 Data residency and outbound connections](#4-data-residency-and-every-outbound-connection) · [5 Backup](#5-backup-and-restore) ·
[6 Upgrade and rollback](#6-upgrade-and-rollback-runbook) · [7 Security notes](#7-security-notes) ·
[8 Operations](#8-day-2-operations) · [9 Test record](#9-test-record-30-october-2026) · [10 Not covered](#10-not-covered-and-limits)

## 1. Sizing

One VM holds everything, so size it for the database first. Throughput numbers in the
[admin guide §8b](admin-guide.md#8b-scaling-and-capacity) were measured on a shared 8-CPU laptop and are relative guidance only.

| Profile | Users (active / named) | vCPU | RAM | Disk (data volume) | Notes |
|---|---|---|---|---|---|
| Minimum (pilot, one team) | 20 / 200 | 2 | 4 GiB | 20 GiB SSD | `preflight.sh` fails below this. Keycloak and the JVM need about 2.5 GiB together |
| **Recommended (department)** | 150 / 2,000 | 4 | 8 GiB | 50 GiB SSD | `preflight.sh` warns below this. Measured shape: about 140 mixed requests/s on 1.5 app CPUs |
| Large (several departments) | 300 / 5,000 | 8 | 16 GiB | 200 GiB SSD | Raise `POSTGRES_SHARED_BUFFERS` to 4GB; consider PostgreSQL on its own VM first |

- **Disk growth**: about 0.8 GB per 100,000 requests (3 tasks, 8 events each) **plus every uploaded file** (request files are stored
  in PostgreSQL, up to 10 MiB each, 50 MiB per request). R0 purges no request history.
- **Backup space**: each backup set is roughly the compressed database size; keep `BACKUP_RETENTION_DAYS` (14) sets, on another disk.
- **Memory split** on the recommended VM: Orqix JVM about 2 GiB (75 % of its container), Keycloak about 1 GiB, PostgreSQL
  `shared_buffers` 512 MB plus page cache, nginx under 50 MB.
- **Connections**: `ORQIX_DB_POOL_SIZE` (15) + 5 (migration) + about 10 (Keycloak) + 10 (operations) must stay under 0.8 × `POSTGRES_MAX_CONNECTIONS` (100).
- **Availability**: one VM is one failure domain. Recovery is a restore from the last backup onto a rebuilt VM (§5); plan the
  backup interval to your recovery-point objective. For pod-failure tolerance use the OpenShift deployment instead.

Host requirements checked by `scripts/preflight.sh`: Linux x86-64, Docker Engine ≥ 24, Compose v2 ≥ 2.20, CPU, RAM,
free disk under the Docker data root and `BACKUP_DIR`, the three host ports free, `.env` complete (no `REPLACE`, no empty
secret, cursor key ≥ 32 characters, `https://` public URL), certificate readable, unexpired, key matching and covering the
host name, and every image already loaded.

## 2. Install (air-gapped)

On a **connected staging machine** with the same `.env` image settings:

```bash
cd deploy/single-vm
scripts/save-images.sh /media/transfer/orqix-images-0.1.0.tar   # pulls, then docker save + .sha256
```

Copy the `deploy/single-vm` folder and the archive to the VM. On the **VM** (no internet):

```bash
scripts/load-images.sh /media/transfer/orqix-images-0.1.0.tar   # verifies the .sha256, docker load
scripts/install.sh --generate-secrets                           # .env from .env.example, random secrets, chmod 600
vi .env                                                         # ORQIX_PUBLIC_URL, ORQIX_IMAGE, ports, KEYCLOAK_ADMIN_ALLOW
cp /secure/orqix.example.gov.crt certs/tls.crt                  # full chain, server certificate first
cp /secure/orqix.example.gov.key certs/tls.key                  # chmod 600
scripts/preflight.sh
scripts/install.sh                                              # docker compose up --pull never, waits for health
scripts/add-user.sh ada.admin --email ada@example.gov --groups orqix-platform-admins
```

`install.sh` never contacts a registry (`--pull never`). Every `up` runs `orqix-migrate` first; the app starts only after
it exited 0. Then follow the [admin guide §5](admin-guide.md#5-setting-up-a-workspace) to create the organisation,
workspace, role bindings and approval groups (the test record in §9 shows the exact calls).

`.env.example` documents every setting in seven sections (instance, images, network exposure, identity, database,
application and outbound connections, backups). Nothing secret is committed: `.env`, `certs/*`, `secrets/*` and `backups/*`
are git-ignored inside the kit.

## 3. Identity

**Bundled Keycloak (default, `COMPOSE_PROFILES=keycloak`).** Production mode (`start`), data in its own PostgreSQL database
`keycloak`, served under `${ORQIX_PUBLIC_URL}/auth` on the same certificate. On first start it imports
`keycloak/orqix-realm.json` with values from `.env` (realm name, client secret, public URL, platform-admin group):

- client `orqix-web`: confidential, authorization code only, PKCE S256 required, redirect URI and post-logout URI bound to
  `ORQIX_PUBLIC_URL`, `groups` claim without paths;
- brute-force protection (10 failures, up to 15 min wait), password policy `length(12) and notUsername and notEmail`,
  login and admin events kept 90 days, no self-registration;
- an empty group `orqix-platform-admins` (`ORQIX_PLATFORM_ADMIN_GROUP`) whose members become Platform Admin.

The realm import runs **only when the realm does not exist**; later changes are made in the admin console
(`${ORQIX_PUBLIC_URL}/auth/admin/`, reachable only from `KEYCLOAK_ADMIN_ALLOW` and the VM itself) or with
`scripts/add-user.sh`. The bootstrap admin `KEYCLOAK_ADMIN` is a temporary master-realm account: after first sign-in create
a named admin and delete or disable it. Users can also be federated from an in-country LDAP/Active Directory in the console
(User federation), which keeps the directory the source of truth.

How the token issuer works: Keycloak is started with `KC_HOSTNAME=${ORQIX_PUBLIC_URL}/auth` and
`KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`. Browsers follow the public authorization and logout URLs; the Orqix container calls
the token, JWK set and userinfo endpoints directly as `http://keycloak:8080/auth/...` on the internal network, so the app
never needs to trust your certificate. Machine-client JWTs are checked against the public issuer.

**External OIDC provider** (an in-country ADFS, another Keycloak, a sovereign-cloud Entra ID): set `COMPOSE_PROFILES=`
(empty) and the six `ORQIX_OIDC_*` URLs in `.env` (§4 of `.env.example`), register a confidential client with redirect URI
`${ORQIX_PUBLIC_URL}/login/oauth2/code/orqix`, PKCE S256 and a `groups` claim (see [admin guide §4](admin-guide.md#4-identity-provider)).
The Orqix container calls the IdP's token and key endpoints itself, so the IdP must be reachable from the backend network
(`BACKEND_INTERNAL=false` when it is outside the VM) and its certificate must be trusted by the JVM: mount a PKCS#12 trust
store into the container and add `-Djavax.net.ssl.trustStore=/path -Djavax.net.ssl.trustStorePassword=...` to
`ORQIX_JAVA_OPTS`. This variant was not tested in §9.

## 4. Data residency and every outbound connection

**All data stays on the VM.** Orqix keeps its entire state in PostgreSQL: schema `orqix` (workflows, releases, requests,
tasks and decisions, comments, the append-only audit trail, run events, **request files and generated documents as
`bytea`**, UI extension files, idempotency records, HTTP sessions) and schema `flowable` (engine runtime and history).
Keycloak keeps users, credential hashes, sessions and events in database `keycloak`. The only Docker volume is
`<project>_pgdata`. Containers run with a read-only root filesystem; `/tmp` is a RAM-backed `tmpfs` (upload spooling above
256 KiB) that vanishes on restart. Logs go to Docker's `json-file` driver on the VM (20 MB × 5 per container) and are
structured ECS JSON; they contain user names and request references, no request data or secrets.

**Network guard.** `orqix`, `keycloak` and `postgres` are attached only to the `backend` network, which is
`internal: true`: Docker gives it no gateway, so no process in those containers can open a connection outside the host
even if misconfigured. Only `proxy` is also on the `edge` network to publish ports. Turning on any connection below needs
two deliberate changes: the feature setting and `BACKEND_INTERNAL=false` (then the in-app egress allowlist still applies).
Verified in §9.

Every outbound connection the application can make (from the source: `OpenRouterClient`, `HttpExecutor`,
`WebhookDispatcher`, `OAuthTokenClient`, `DelegatedOAuth`, `McpClient`, `MicrosoftGraphDocuments`,
`KafkaConsumerRunner`, `ClamAvScanner`, `StageMailTransport`, the OIDC client):

| # | Connection | Destination | Default in this kit | Data that leaves | How to keep it off |
|---|---|---|---|---|---|
| 1 | AI assistance (drafting/explaining workflows, AI review) | `ORQIX_AI_BASE_URL` (OpenRouter, `openrouter.ai:443`) | **Off**: `OPENROUTER_API_KEY` empty | Workflow descriptions, node catalog, group names, connection schemas, document excerpts for AI review | Leave the key empty (AI entry points hidden, endpoints answer `422 AI_NOT_CONFIGURED`). To use AI in-country, point `ORQIX_AI_BASE_URL` at an OpenAI-compatible gateway you host (untested here) |
| 2 | REST connectors (workflow steps calling other systems) | Binding `baseUrl` per connection | **Off**: `ORQIX_EGRESS_ALLOWED_HOSTS` empty | The request fields the workflow maps into the call | Keep the allowlist empty; for in-country systems list only their `host:port` (private ranges also need `ORQIX_EGRESS_ALLOWED_PRIVATE_HOSTS`). Plain HTTP is forced off |
| 3 | Outbound webhooks | Subscription URLs | Off (same allowlist) | Event payloads | As 2 |
| 4 | OAuth token / delegated OAuth for connections | Token, authorization, revoke URLs of the connection | Off (same allowlist) | Client credentials, user consent codes | As 2 |
| 5 | MCP tool servers and external agents | Configured endpoint | Off (same allowlist) | Tool arguments | As 2 |
| 6 | Microsoft Graph documents | `graph.microsoft.com` and download hosts | Off (same allowlist) | Document requests, OAuth tokens | Do not allow `graph.microsoft.com:443`; use document upload instead |
| 7 | Kafka triggers | Broker bootstrap servers | **Off**: `ORQIX_KAFKA_MODE=off` forced in `compose.yaml` | Consumer group metadata | Leave as is |
| 8 | E-mail notifications | Your SMTP relay (`ORQIX_MAIL_HOST`) | **Off**: `ORQIX_MAIL_ENABLED=false` | Notification text, recipient addresses | Keep false, or use an in-country relay |
| 9 | Virus scanning | clamd (`ORQIX_CLAMAV_HOST:3310`) | **Off**: `ORQIX_CLAMAV_ENABLED=false` | Uploaded file bytes | Run clamd on the VM's backend network (add a `clamav` service; load signature updates offline with `freshclam` mirrors or `.cvd` files) |
| 10 | OIDC back-channel | Bundled Keycloak on the internal network | On, internal only | Authorization codes, tokens | External IdP only: its token/JWK/userinfo URLs (§3) |
| 11 | Machine-client key fetch (JWT bearer) | Same JWK set URI as 10 | On, internal only | None | As 10 |

Not outbound from the application, but listed for completeness:

- **Browsers** load the UI only from `ORQIX_PUBLIC_URL`: the SPA is bundled in the image with no CDN, web font or
  analytics call. Keycloak's login theme is served by Keycloak itself.
- **Keycloak** makes no outbound calls unless you configure identity brokering, LDAP federation or SMTP in its console
  (and with `BACKEND_INTERNAL=true` it could not reach anything outside the host anyway).
- **PostgreSQL and nginx** make none. nginx is the one container on the `edge` network, which Docker routes like any bridge
  network: for a strict air gap also block outbound traffic from the VM in the host firewall or at the network perimeter
  (allow inbound 443/80 and your monitoring and backup flows only).
- **Docker** itself contacts a registry only on `pull`; the scripts always pass `--pull never`, and `upgrade.sh` pulls only
  with `--pull`.

## 5. Backup and restore

`scripts/backup.sh` runs online (users keep working) and writes one folder `BACKUP_DIR/<project>-<UTC stamp>[-label]/`:

| File | Content |
|---|---|
| `orqix.dump` | `pg_dump -Fc` of database `orqix`: **all Orqix data including request files/attachments** (stored in PostgreSQL), audit, history, engine state |
| `keycloak.dump` | `pg_dump -Fc` of database `keycloak` (bundled IdP) |
| `globals.sql` | `pg_dumpall --globals-only` (roles), reference only |
| `config.tar.gz` | `.env` (stored as `env`), `secrets/`, realm file, proxy template (`--no-config` leaves it out) |
| `pgdata.tar.gz` | only with `--volume-snapshot`: raw copy of the volume, stack stopped for the copy |
| `MANIFEST` | images and image IDs, schema version, PostgreSQL version, row counts of key tables |
| `SHA256SUMS` | checksums of every file; verified before the script reports success, and again by `restore.sh` |

Each dump is read back with `pg_restore -l` before it is accepted. Sets older than `BACKUP_RETENTION_DAYS` are deleted
after a successful backup. **A set contains every secret and all personal data**: keep `BACKUP_DIR` on an encrypted disk,
copy it to a second site inside the jurisdiction, and restrict access like the production database.

**Schedule** (cron on the VM, as a user in the `docker` group):

```cron
# daily online backup 01:30, weekly cold volume copy Sunday 03:00, off-site copy 02:30
30 1 * * *  cd /opt/orqix/deploy/single-vm && scripts/backup.sh >> /var/log/orqix-backup.log 2>&1
0  3 * * 0  cd /opt/orqix/deploy/single-vm && scripts/backup.sh --volume-snapshot --label weekly >> /var/log/orqix-backup.log 2>&1
30 2 * * *  rsync -a --delete /opt/orqix/deploy/single-vm/backups/ backup-host.internal:/srv/orqix-backups/
```

Recommended: daily online backups (recovery point ≤ 24 h; run hourly if your RPO is tighter, the dump is cheap at R0 sizes),
a weekly cold copy, an alert when no set younger than 26 h exists, and a **restore rehearsal every quarter** on a spare VM.

**Restore** (`scripts/restore.sh <set>`): verifies `SHA256SUMS` (refuses a damaged set), asks you to type the project name
(`--yes` skips), stops `proxy`, `orqix` and `keycloak`, starts `postgres` (an empty volume is initialised with the roles from
`.env`), drops and re-creates `orqix` and `keycloak` with `pg_restore --create --exit-on-error`, re-applies the role
passwords from `.env`, compares every row count with the `MANIFEST` (refuses to start on a mismatch), then starts the stack
and runs `health.sh`. `--with-config` also puts back `.env` and `secrets/` from the set (rebuilt VM);
`--image-from-backup` sets `ORQIX_IMAGE` to the image recorded in the set (rollback).

After restoring **production** data, follow the admin guide runbook "Restore from backup": a restore can roll back Orqix's
record of work that remote systems already performed, so check runs that were in flight at backup time before resuming.

## 6. Upgrade and rollback runbook

Before: read the release notes for required migrations, carry the new image to the VM (`save-images.sh` on staging), and
schedule about five minutes of downtime (one app container: users are signed out briefly, in-flight jobs resume).

```bash
scripts/upgrade.sh --image orqix/orqix:0.2.0 --archive /media/transfer/orqix-0.2.0.tar
```

| Step | What happens | On failure |
|---|---|---|
| 1 | Preflight (`--running`), `docker load` (archive `.sha256` verified when present) or `--pull`; image must exist locally; confirmation (`--yes` skips) | Stops; nothing changed |
| 2 | `backup.sh --label pre-upgrade`; schema version recorded | Stops; nothing changed |
| 3 | `ORQIX_IMAGE` set in `.env`; previous file kept as `.env.pre-upgrade-<stamp>` | |
| 4 | `orqix` stopped; the **new image's** migration job runs (Flyway + Flowable schema update, forward-only); `up -d`; wait for readiness (7 min max) | Automatic rollback |
| 5 | `health.sh`: containers healthy, readiness UP, HTTPS UI, OIDC discovery issuer | Automatic rollback |

**Automatic rollback**: `ORQIX_IMAGE` is set back. If the schema version did not change, the old image is simply started
again. If migrations ran, the pre-upgrade backup is restored with `restore.sh --image-from-backup` (migrations are
forward-only; an old image must not run on a newer schema). `--no-rollback` leaves a failed upgrade in place for diagnosis.

**Manual rollback later** (problem found after a successful upgrade): note that data written since the upgrade is lost.

```bash
ls backups/ | grep pre-upgrade                                   # pick the set made by the upgrade
scripts/restore.sh backups/<project>-<stamp>-pre-upgrade --image-from-backup
```

Rehearse every Flowable engine upgrade on a restored copy first (admin guide §8): restore last night's set on a spare VM
with the same kit and run `upgrade.sh` there.

## 7. Security notes

- Only nginx publishes ports. PostgreSQL has none; the management port (health, Prometheus) is bound to `127.0.0.1`.
- TLS 1.2/1.3 only, HSTS, `server_tokens off`; HTTP redirects to `ORQIX_PUBLIC_URL`. Session cookie `Secure`, `HttpOnly`, `SameSite=Lax`.
- Orqix containers: non-root UID in group 0, read-only root filesystem, all capabilities dropped, `no-new-privileges`
  (same as OpenShift restricted SCC). The app connects as `orqix_app` (DML only); only `orqix-migrate` uses the DDL role.
- `/auth/admin/` (Keycloak admin console and API) is limited to `KEYCLOAK_ADMIN_ALLOW` and the VM itself.
- `.env` holds every secret: `chmod 600`, owned by the operator. Rotate the Keycloak bootstrap admin after installation.
- Images are pinned by tag in `.env`; for stronger supply-chain control pin digests (`image@sha256:...`) and record them in
  your change log (`MANIFEST` of each backup records the image IDs in use).

## 8. Day-2 operations

| Task | Command |
|---|---|
| Health | `scripts/health.sh` (exit 0 = healthy); Prometheus scrape `http://127.0.0.1:${MANAGEMENT_PORT}/actuator/prometheus` |
| Logs | `docker compose -p <project> logs -f orqix` (or `keycloak`, `proxy`, `orqix-migrate`) |
| Add a user / reset a password | `scripts/add-user.sh <user> --groups g1,g2` (password prompted, or `ORQIX_USER_PASSWORD`) |
| Rotate the certificate | Replace `certs/tls.crt` and `tls.key`, `scripts/preflight.sh --running`, `docker compose -p <project> restart proxy` |
| Rotate a database password | `ALTER ROLE ... PASSWORD` via `docker compose exec postgres psql -U postgres`, update `.env`, `docker compose up -d` |
| Rotate the OIDC client secret | Regenerate in the Keycloak console (client `orqix-web` → Credentials), update `ORQIX_OIDC_CLIENT_SECRET`, `up -d` |
| Stop / start | `docker compose -p <project> --env-file .env stop` / `scripts/install.sh` |
| Remove everything incl. data | `docker compose -p <project> --env-file .env --profile keycloak down -v` (take a backup first) |

All `docker compose` commands need `--env-file .env -p <COMPOSE_PROJECT_NAME>` from `deploy/single-vm`; the scripts pass
them for you.

## 9. Test record (30 October 2026)

**Environment.** Windows 11 laptop, 8 logical CPUs, 32 GB RAM, Docker Desktop 28.5.2 (Linux containers, WSL2), Compose
2.40.3, scripts run from Git Bash. The machine was shared with about 15 other containers (four other Orqix stacks being
built and tested at the same time), so durations are slow and only indicative. Isolated test instance:
`COMPOSE_PROJECT_NAME=orqix-svm-test`, `ORQIX_PUBLIC_URL=https://localhost:28443`, ports 28443/28080/28081 bound to
`127.0.0.1`, self-signed certificate from `scripts/dev-cert.sh`, `KEYCLOAK_ADMIN_ALLOW=all`. Images:
`orqix/orqix:develop-df74aa8` (built from `df74aa8`, the code on this branch), `postgres:18-alpine` (18.6),
`quay.io/keycloak/keycloak:26.8.0`, `nginx:1.27-alpine`. The development stack on ports 8080/8180/5432 and its volumes
were not touched.

| # | Step | Command | Result |
|---|---|---|---|
| 1 | Secrets | `scripts/install.sh --generate-secrets`, then edit project name, URL, image, ports | `.env` with 7 random secrets; nothing secret in git |
| 2 | Preflight | `scripts/preflight.sh` | **ready**, 3 warnings: Docker data root not visible (Docker Desktop VM), `.env` mode 644 (NTFS ignores `chmod`), certificate valid under 30 days (test certificate). CPU 8, RAM 32 GiB, ports free, key matches, SAN covers `localhost`, 4 images present |
| 3 | Install | `scripts/install.sh` | 99 migrations (to V208) applied by `orqix-migrate`; Keycloak realm imported with `.env` values; all containers healthy; readiness UP; HTTPS UI 200; OIDC issuer `https://localhost:28443/auth/realms/orqix`. 6 min 25 s |
| 4 | No outbound route | `docker exec <container> ... /dev/tcp/1.1.1.1/443` in `orqix`, `keycloak`, `postgres` | **"Network is unreachable"** in all three; `orqix` -> `keycloak:8080` works. `proxy` (edge network) can reach out, hence the firewall note in §4 |
| 5 | AI off | app log | `AI assistance disabled: no provider key configured (rules-only mode).` |
| 6 | Users | `scripts/add-user.sh ada.admin --email ... --groups orqix-platform-admins,svm-admins` (and `rex.requester`/`svm-requesters`, `mia.manager`/`svm-managers`) | users and groups created in realm `orqix`; re-running updates them |
| 7 | Sign-in and workflow | `python tests/backup_restore_check.py before` | Each user signed in through the Keycloak login form over HTTPS (authorization code + PKCE). Platform admin created organisation, workspace, role bindings, `workflow.start` grant and approval group `managers`; published `examples/simple-review.json` as release 1. **ORQ-1001** started by Rex, approved by Mia, **completed / Accepted**, history `RUN_STARTED, TASK_CREATED, TASK_CLAIMED, TASK_DECIDED, RUN_COMPLETED`. **ORQ-1002** started with a 64,026-byte file attached, left waiting at the review |
| 8 | Backup | `scripts/backup.sh` | Set `orqix-svm-test-20261031T033454Z`: `orqix.dump` 652,749 B, `keycloak.dump` 266,353 B, `globals.sql`, `config.tar.gz`, `MANIFEST` (counts: 2 workflows, 2 runs, 8 run events, 1 decision, 30 audit events, 1 file), `SHA256SUMS` verified. 4 min 6 s |
| 9 | Damaged set refused | copy of the set with one byte of `orqix.dump` changed, `scripts/restore.sh <copy> --yes` | `orqix.dump: FAILED`, "checksum mismatch: the backup set is damaged, not restoring", exit 1, nothing stopped |
| 10 | Destroy | `docker compose -p orqix-svm-test --env-file .env --profile keycloak down -v` | containers, both networks and volume `orqix-svm-test_pgdata` removed (`docker volume ls`: none left) |
| 11 | Restore | `scripts/restore.sh backups/orqix-svm-test-20261031T033454Z --yes` | checksums OK; fresh volume initialised; both databases restored; all 9 row counts equal to the `MANIFEST`; stack healthy. 5 min 36 s |
| 12 | Data back | `python tests/backup_restore_check.py after` | Users (from `keycloak.dump`) sign in. **ORQ-1001**: reference, state, outcome, end time, input, all 5 events (sequence, actor, timestamp) and the task identical to before. **ORQ-1002**: still waiting; the file downloads with the **same SHA-256**; Mia approved it and it **completed** |
| 13 | Upgrade, same tag, air-gapped path | `docker save` of the image to a tarball + `.sha256`, then `scripts/upgrade.sh --image orqix/orqix:develop-df74aa8 --archive orqix-image.tar --yes` | archive checksum OK; `docker load`; pre-upgrade backup; migration job "Schema orqix is up to date. No migration necessary"; app restarted healthy; `health.sh` green. 21 min 33 s, 6 min of it `docker load` of 234 MB on this machine |
| 14 | Automatic rollback | `scripts/upgrade.sh --image alpine:latest --yes` (an image that cannot run Orqix) | pre-upgrade backup; app container crash-looped; "orqix restarted while starting", "schema unchanged: starting the old image"; old image healthy, `health.sh` green; exit 1 with "rolled back to orqix/orqix:develop-df74aa8". 10 min 35 s |
| 15 | Manual rollback runbook | `.env` set to `ORQIX_IMAGE=alpine:latest`, then `scripts/restore.sh backups/<...>-pre-upgrade --yes --image-from-backup` | `ORQIX_IMAGE` reset to the image in the `MANIFEST`; restore with 9/9 counts matching; healthy; ORQ-1001 history and ORQ-1002 (completed) checked again. 5 min 8 s |
| 16 | Kit check script | `tests/backup_restore_check.py before` then `after` on a new workspace | all checks PASS (exit 0) |
| 17 | Teardown | `docker compose -p orqix-svm-test --env-file .env --profile keycloak down -v` | test containers, networks and volume removed; local `.env`, certificate and backup sets deleted |

Problems found by this test and fixed in the kit before it was committed: the migration job also needs the OIDC client
settings (it boots the same Spring context; now shared through a YAML anchor); Keycloak's user profile needs an email,
so `add-user.sh` requires `--email` (otherwise the first sign-in stops at "update profile"); the Keycloak image has no
`awk`; `restore.sh`'s row-count loop lost its input to `docker exec` and checked only the first table (now reads on a
separate descriptor and fails unless every table was checked); a crash-looping container now fails the health wait at
once instead of after 7 minutes; several Git-Bash-only path problems (`C:` read as a tar remote host, native `openssl`
and `docker` given MSYS paths).

Commands to repeat this on a test VM (users' password in `SVM_PW`, never a production system):

```bash
cd deploy/single-vm
scripts/install.sh --generate-secrets && vi .env && scripts/dev-cert.sh && scripts/preflight.sh && scripts/install.sh
for u in "ada.admin orqix-platform-admins,svm-admins" "rex.requester svm-requesters" "mia.manager svm-managers"; do
  set -- $u; ORQIX_USER_PASSWORD="$SVM_PW" scripts/add-user.sh "$1" --email "$1@example.test" --groups "$2"; done
export SVM_URL=https://localhost:28443 SVM_CA=certs/tls.crt SVM_DEFINITION=../../examples/simple-review.json
python tests/backup_restore_check.py before
scripts/backup.sh
docker compose -p orqix-svm-test --env-file .env --profile keycloak down -v
scripts/restore.sh backups/<set> --yes
python tests/backup_restore_check.py after
scripts/upgrade.sh --image "$(grep ^ORQIX_IMAGE= .env | cut -d= -f2)" --yes
```

## 10. Not covered and limits

Not tested on the Windows test machine, to be done on the first real Linux VM:

- **A real Linux host.** Everything ran under Docker Desktop (WSL2) from Git Bash. Not exercised: the Docker data root
  disk check (not visible from Windows), `chmod 600` on `.env` (NTFS), binding ports 443/80 (the test used 28443/28080),
  a non-`localhost` host name, cron.
- **A CA-issued certificate** (the test used `dev-cert.sh`; trust was given to the client explicitly).
- **External OIDC provider** (`COMPOSE_PROFILES=` empty, `ORQIX_OIDC_*`) and the JVM trust store for a private CA.
- **An upgrade that applies new migrations.** Only the same tag was tested, so the "schema changed, restore the
  pre-upgrade backup" branch of the automatic rollback did not run by itself; the restore it calls
  (`restore.sh --image-from-backup`) was tested in step 15.
- `backup.sh --volume-snapshot`, `restore.sh --with-config`, `save-images.sh` (needs a connected machine; the load path
  was tested with a `docker save` tarball of the Orqix image).
- Enabling an outbound connection (`BACKEND_INTERNAL=false` with an egress allowlist, mail, ClamAV, AI gateway).
- Capacity: the sizing table comes from the admin guide's laptop measurements, not from a VM.

Limits by design: one VM is one failure domain (no high availability; recovery = restore, RPO = backup interval); upgrades
take a few minutes of downtime; the Keycloak bootstrap admin is temporary and must be replaced after installation; the
kit does not encrypt backup sets itself (use an encrypted volume or your backup tool's encryption).
