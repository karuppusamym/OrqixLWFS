# Orqix backups, point-in-time restore and restore drills (CG-P03)

Build note: `docs/build/S150-observability-scale-backups-admission.md`. Closes T-RES-06 (restore a backup to an
isolated database) locally. The single-VM kit keeps its own `deploy/single-vm/scripts/backup.sh` / `restore.sh`.

## What is backed up

Everything Orqix stores is in one PostgreSQL database (`orqix`, schemas `orqix` and `flowable`): requests and their
history, tasks, engine state and history, the effect journal, the audit trail, files (stored as `bytea`), sessions and
configuration. Pods are stateless. Mounted connection secrets and the identity provider are backed up with the platform
that owns them.

| Layer | Compose (single host) | OpenShift |
|---|---|---|
| Logical backup (daily) | `backup` service of `deploy/compose/docker-compose.backup.yml` runs `scripts/orqix-backup.sh` at start and every `BACKUP_INTERVAL_SECONDS` (default 24 h) | CronJob `orqix-backup` at 01:30 UTC (`deploy/backup/kustomization.yaml`) |
| WAL archive (continuous) | `archive_mode=on`, `archive_timeout=300` into `/backups/wal` (same overlay) | the database platform's WAL archiving (operator or managed service) |
| Base backup (weekly) | `deploy/backup/compose-base-backup.sh` | the database platform's base backups |
| Restore drill (quarterly) | `docker compose ... run --rm backup /scripts/orqix-restore-drill.sh` | CronJob `orqix-restore-drill`, 04:00 UTC on the 5th of Jan, Apr, Jul, Oct |
| Retention | 14 days of logical sets (`BACKUP_RETENTION_DAYS`), 4 base backups (`BASE_BACKUP_KEEP`); WAL older than the oldest base backup can be deleted | PVC `orqix-backups` (size it, replicate it off-cluster) |

Recovery objectives with these defaults: **RPO** 24 h from logical sets alone, 5 minutes with WAL archiving
(`archive_timeout`); **RTO** = restore time of the newest set or base backup plus WAL replay (the drill records the
restore time, see the build note for the measured value).

A logical set (`orqix-<UTC stamp>/`) holds `orqix.dump` (`pg_dump -Fc`, one consistent snapshot), `MANIFEST` (row
counts of 22 tables and one sample request, read **in the dump's snapshot**) and `SHA256SUMS`. Sets contain personal
data: keep the volume encrypted and copy it off the host or cluster.

## Compose

```bash
cd deploy/compose
docker compose -f docker-compose.yml -f docker-compose.backup.yml --profile app --profile backup up -d
docker compose -f docker-compose.yml -f docker-compose.backup.yml run --rm backup /scripts/orqix-backup.sh    # one now
docker compose -f docker-compose.yml -f docker-compose.backup.yml run --rm backup /scripts/orqix-restore-drill.sh
../backup/compose-base-backup.sh
```

The first `up` with the overlay restarts PostgreSQL once (archive settings). Copy the `orqix-backups` volume off the host
(for example `docker run --rm -v orqix_orqix-backups:/b -v $PWD:/out alpine tar czf /out/orqix-backups.tgz -C /b .`).

## OpenShift

1. Create a backup role with `pg_read_all_data` and a drill role with `CREATEDB` (ideally on a separate drill server),
   and the Secrets `orqix-backup-db` and `orqix-restore-drill-db` (`openshift/secrets.example.yaml` shows the keys).
2. Replace the storage class in `openshift/pvc.yaml` and the client image in both CronJobs (same PostgreSQL major as the
   server, mirrored and digest-pinned).
3. `oc apply -n orqix-<env> -k deploy/backup`. The base NetworkPolicies already allow DNS and PostgreSQL egress for pods
   labelled `app.kubernetes.io/part-of=orqix`.
4. Run a drill now: `oc create job -n orqix-<env> --from=cronjob/orqix-restore-drill orqix-restore-drill-now`, then
   `oc logs job/orqix-restore-drill-now`. The report is also kept on the PVC (`/backups/drills/`).
5. Point-in-time restore: enable WAL archiving and base backups in the database platform (for example CloudNativePG
   `backup.barmanObjectStore` with a `ScheduledBackup`, Crunchy `pgBackRest`, or the managed service's PITR window of at
   least 7 days). Rehearse a PITR restore into a new cluster once a quarter, together with the drill.

`tools/validate-manifests/validate_manifests.py` renders `deploy/backup` and checks the CronJobs (hardening, UTC
schedule, no concurrent runs, Secret references only, PVC and script ConfigMap).

## Restore drill (quarterly, and after every PostgreSQL major upgrade)

`orqix-restore-drill.sh` restores the newest set (or the one given) into a new database `orqix_drill_<timestamp>`, with
`--no-owner --no-privileges`, then compares every `count.*` line and the `sample.request` line of the MANIFEST with the
restored copy, writes `drills/drill-<stamp>.json` (restore seconds, checks, mismatches, requests, open engine tasks,
releases, effects to reconcile) and drops the scratch database (`DRILL_KEEP=true` keeps it). Exit status 1 on any
mismatch. Production is never touched: the scratch name is generated and must not exist.

Record each drill's report in the operations log. A failed drill is an incident: the backups are not restorable until
the cause is fixed and a drill passes.

## Point-in-time restore (Compose runbook)

Use when data must be recovered to a moment (an accidental bulk cancel, a bad import), not just to last night.

1. Find the target time in UTC, just **before** the damaging change: the audit log (`audit_event.occurred_at`) or the
   request timeline names it.
2. Restore into a scratch server and inspect it, without touching the live stack:
   ```bash
   deploy/backup/compose-pitr-restore.sh --target "2026-11-09 14:05:00+00" --keep --port 55432
   psql -h 127.0.0.1 -p 55432 -U postgres orqix      # check the data you need is there
   ```
   The script picks the newest base backup taken before the target (or `--base`), verifies its checksums, replays the
   archived WAL up to the target (exclusive) and promotes. It prints requests, events and effects to reconcile.
3. Either copy the needed rows back (export from the scratch server, import through the API or SQL with review), or
   replace the live database:
   1. `docker compose stop orqix orqix-worker-1 orqix-worker-2` (every pod: nothing may write).
   2. `pg_dump -Fc` the scratch server, then `dropdb`/`createdb` `orqix` on the live server and `pg_restore` into it
      (as `deploy/single-vm/scripts/restore.sh` does), or swap the data volume.
   3. Start the migration job, then the pods. Flyway is a no-op for the same image.
4. **Before users resume**: requests that were in flight at the target time may have called remote systems after it.
   Open Operations, filter effects in state `unknown` or `inFlight` (the restore prints how many), and reconcile each
   (confirm applied or not applied) before retrying. Idempotent effects are re-sent with the same key and the remote
   system de-duplicates them; non-idempotent ones are never re-sent blindly (S5 section 6).
5. Record the restore (time, target, base backup, WAL range, reconciled effects) in the operations log.
