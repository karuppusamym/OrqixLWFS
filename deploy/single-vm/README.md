# Orqix sovereign single-VM kit

Orqix, PostgreSQL, Keycloak and a TLS reverse proxy on **one Linux VM**, with **no outbound internet at runtime**.
Full guide (sizing, data residency, every outbound connection, backup schedule, upgrade and rollback runbook, test
record): [docs/sovereign-deployment.md](../../docs/sovereign-deployment.md).

```bash
# connected staging machine: carry the images across
scripts/save-images.sh /media/usb/orqix-images.tar

# the VM (air-gapped)
scripts/load-images.sh /media/usb/orqix-images.tar
scripts/install.sh --generate-secrets      # .env with random secrets; then edit ORQIX_PUBLIC_URL, ORQIX_IMAGE, ports
cp /path/to/your/{tls.crt,tls.key} certs/  # bring your own certificate
scripts/preflight.sh                       # CPU, RAM, disk, Docker, ports, .env, certificate, images
scripts/install.sh                         # start, migrate, wait until healthy
scripts/add-user.sh ada.admin --groups orqix-platform-admins

scripts/backup.sh                          # online backup with SHA-256 checksums
scripts/restore.sh backups/<set>           # verify checksums, restore, compare row counts, health check
scripts/upgrade.sh --image orqix/orqix:<new> --archive /media/usb/orqix-<new>.tar   # backup, migrate, check, roll back on failure
scripts/health.sh
```

| File | Purpose |
|---|---|
| `compose.yaml` | `proxy` (nginx, only published ports), `orqix`, `orqix-migrate` (runs before every start), `keycloak` (profile `keycloak`), `postgres`. App, IdP and database share an `internal` network with no route out |
| `.env.example` | Every setting, documented. Copy to `.env` (git-ignored) |
| `proxy/default.conf.template` | TLS termination, `/` -> Orqix, `/auth` -> Keycloak, admin console restricted, management port on localhost |
| `keycloak/orqix-realm.json` | Realm imported on first start (client `orqix-web`, PKCE S256, `groups` claim, brute-force protection, password policy) |
| `postgres/init/` | Roles `orqix_migrator`, `orqix_app`, `keycloak` and their databases on an empty volume |
| `scripts/` | `preflight`, `install`, `health`, `add-user`, `backup`, `restore`, `upgrade`, `save-images`, `load-images`, `dev-cert` (tests only) |
| `tests/backup_restore_check.py` | Acceptance check through HTTPS: sign-in, a workflow run to completion, a request with a file, compared before and after a backup/restore (doc §9) |
| `certs/`, `secrets/`, `backups/` | Your certificate, connection secret files, backup sets. Never committed |

This kit is separate from the development stack in `deploy/compose` (dev realm, test users, stub) and from the
OpenShift manifests in `deploy/openshift`.
