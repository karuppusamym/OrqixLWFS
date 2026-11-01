# Orqix on OpenShift (Kustomize)

Base plus three overlays. No Helm dependency (BD-15). Everything here is a reference deployment: environment owners
complete the `REPLACE` placeholders (registry, hostnames, CIDRs, IdP issuer) and create the Secrets.

```
deploy/openshift/
├── base/                       Deployment, Service, Route, ConfigMap (generated), ServiceAccount,
│                               NetworkPolicies, PodDisruptionBudget, HorizontalPodAutoscaler, migration Job
│   ├── config.env              non-secret orqix.* / Spring configuration (env-var form)
│   └── secrets.example.yaml    NOT applied: the Secret names and keys the workloads reference
├── overlays/{dev,int,prod}/    namespace, image, hostnames, CIDRs, egress allowlist, per-environment config.env
├── overlays/prod-split/        prod plus the API/worker split (example and validation target)
├── components/split-roles/     opt-in API/worker split (docs/adr/0004)
└── optional/servicemonitor.yaml   needs the Prometheus Operator CRDs; apply separately
```

Render and validate (no cluster needed):

```bash
kubectl kustomize deploy/openshift/overlays/prod          # or: oc kustomize ...
python tools/validate-manifests/validate_manifests.py     # 150+ structural checks over the four overlays
kubectl kustomize deploy/openshift/overlays/int | docker run --rm -i ghcr.io/yannh/kubeconform:v0.7.0 \
    -strict -ignore-missing-schemas -kubernetes-version 1.31.0 -summary -
```

## What the manifests encode

| Requirement | Where |
|---|---|
| 2 replicas, `RollingUpdate` with `maxUnavailable: 0`, `terminationGracePeriodSeconds: 60`, 5 s `preStop` | `base/deployment.yaml` (int/prod remove `replicas`; the HPA owns it) |
| Probes on the management port 8081: startup + liveness `/actuator/health/liveness`, readiness `/actuator/health/readiness`. Liveness never checks the database | `base/deployment.yaml` |
| Requests 500m / 1Gi, limits 2 / 2Gi (dev overlay is smaller) | `base/deployment.yaml` |
| `runAsNonRoot`, no privilege escalation, read-only root filesystem, `drop: [ALL]`, seccomp `RuntimeDefault`; UID/fsGroup assigned by the `restricted-v2` SCC; writable `emptyDir` at `/tmp` | `base/deployment.yaml` |
| Connection secrets: Secret `orqix-connection-secrets` mounted read-only at `/var/run/secrets/orqix` (one file per `secretRef`, read on use; optional so a pod starts before the first connection exists) | `base/deployment.yaml` |
| Configuration from a hash-named ConfigMap (a change rolls the pods); DB, OIDC and cursor secrets from `secretKeyRef` | `base/config.env`, `base/secrets.example.yaml` |
| Route: edge TLS, insecure traffic redirected, HSTS, per-source-IP rate limits, no sticky cookie (sessions are in PostgreSQL). It targets port `http` (8080) only; **8081 is never routed** | `base/route.yaml` |
| NetworkPolicy: default deny; ingress 8080 from the router namespace only, 8081 from the monitoring namespaces only; egress to DNS, PostgreSQL, the IdP and allowlisted endpoints. The migration Job gets DNS + PostgreSQL only | `base/networkpolicy.yaml` |
| PodDisruptionBudget `minAvailable: 1`; HPA 2..4 | `base/pdb.yaml`, `base/hpa.yaml` |
| HPA ceiling vs the database: `pods x 15 + 5 + 10 <= 0.8 x max_connections`; 4 pods = 75 <= 80 at `max_connections = 100` (S5 section 11). Raising `maxReplicas` needs a larger `max_connections` and a check of external API rate limits | `base/hpa.yaml` |

## Optional: separate API and worker pods

By default every pod serves users **and** runs the engine job executor, the scheduled jobs and the Kafka consumers. The
`components/split-roles` component ([ADR 0004](../../docs/adr/0004-runtime-roles-before-microservices.md)) splits them:

| Deployment | Profiles | Does | Replicas | Connections |
|---|---|---|---|---|
| `orqix` (API) | `prod,api` | requests only; no engine jobs, no scheduled work | HPA 2..3 | 10 per pod |
| `orqix-worker` | `prod,worker` | engine jobs (BPMN and CMMN), scheduled jobs, Kafka; not behind the Service | 2 | 15 per pod |

Budget: `3 x 10 + 2 x 15 + 5 + 10 = 75 <= 80`. Workers get monitoring ingress, DNS, PostgreSQL and connection egress, but
no router ingress and no IdP egress. Asynchronous steps after a user action start on a worker within about one second.
An API deployment without workers is invalid: timers, retries, callbacks, webhooks, reminders and Kafka would stop.

Enable it by adding the component to an overlay; `overlays/prod-split` shows the prod case. A component's new resources
need the namespace and image set in the overlay that lists it, which is why `prod-split` repeats both. Not verified on a
cluster and not load-tested against the all-in-one layout; keep the default until both are done.

## Rollout order (migration Job first)

Pods run with schema update **disabled** (`spring.flyway.enabled=false`, `flowable.database-schema-update=false`); the
migration Job is the only writer of DDL. Run it before every rollout. Migrations are additive (expand/contract) so the
previous version keeps serving while the Job executes. Resources carry the label `orqix.io/stage` so one overlay can be
applied in steps:

```bash
ENV=int   # dev | int | prod
oc apply -k deploy/openshift/overlays/$ENV -l orqix.io/stage=prerequisites   # ConfigMap, ServiceAccount, NetworkPolicies
oc -n orqix-$ENV delete job orqix-migrate --ignore-not-found                  # a Job's pod template is immutable
oc apply -k deploy/openshift/overlays/$ENV -l orqix.io/stage=migration       # the migration Job
oc -n orqix-$ENV wait --for=condition=complete job/orqix-migrate --timeout=15m
oc apply -k deploy/openshift/overlays/$ENV -l orqix.io/stage=app             # Deployment, Service, Route, PDB, HPA
oc -n orqix-$ENV rollout status deployment/orqix --timeout=10m
```

* If the Job fails the pipeline stops; nothing of the new version is rolled out. A migration is never retried
  automatically (`backoffLimit: 0`): restore point first, fix forward second. Rehearse engine (Flowable) schema upgrades
  against a restored database before production (docs/03-architecture.md).
* GitOps: the Job carries Argo CD `PreSync` hook annotations (`hook-delete-policy: BeforeHookCreation`), so the controller
  runs it before the sync and replaces the previous Job. Other tools can use the label steps above.
* The `-l` selector with `-k` is standard `kubectl`/`oc` behaviour but was not exercised here (no cluster was available);
  the staged label assignment itself is asserted by `validate_manifests.py`.

## Secrets (references only)

`base/secrets.example.yaml` lists the Secrets the workloads reference. Create them with the platform's tooling (External
Secrets, Vault, sealed secrets, or an administrator's `oc create secret`). A missing Secret keeps the pod in
`CreateContainerConfigError`, which is the intended loud failure.

| Secret | Keys | Used by |
|---|---|---|
| `orqix-db-app` | `password` | pods (role `orqix_app`, DML only) |
| `orqix-db-migrator` | `password` | migration Job (role `orqix_migrator`, DDL owner) |
| `orqix-app` | `cursor-secret`, `oidc-client-secret` | pods |
| `orqix-connection-secrets` | one key per connection `secretRef` | pods, mounted as files |
| `orqix-ai` (optional) | `openrouter-api-key` | pods, as `OPENROUTER_API_KEY`; when absent AI assistance is off and everything else works |

**AI egress.** When `orqix-ai` is set, pods call `https://openrouter.ai/api/v1` (port 443). OpenRouter sits behind a CDN,
so a fixed `ipBlock` is impractical: allow it through the platform egress proxy or an OVN-Kubernetes `EgressFirewall`
`dnsName: openrouter.ai` rule (cluster-admin), and record the decision with D06. Without that path the AI endpoints
return `503 AI_PROVIDER_UNAVAILABLE` and nothing is created.

## Placeholders to complete per environment

* `images` (overlay): registry path and tag; use a digest in `prod` (`kustomize edit set image orqix=registry/path@sha256:...`).
* `Route` host, `ORQIX_DB_URL`, IdP issuer URIs, bootstrap admin group, `ORQIX_EGRESS_ALLOWED_HOSTS` (overlay `config.env`).
* NetworkPolicy CIDRs for PostgreSQL, the IdP and each approved connection endpoint (documentation addresses from RFC 5737
  are used so an unreplaced placeholder blocks traffic instead of opening it). The application also enforces
  `orqix.egress.allowed-hosts`; the policy is the platform backstop (decision D06). For a database inside the cluster replace
  the `ipBlock` with a `podSelector`.
* Router namespace label: the default ingress controller namespace carries `policy-group.network.openshift.io/ingress`.
* Custom TLS certificate for the Route: supply through an overlay patch from the secret tooling; never commit key material.
* Unresolved items that belong to decisions D05 (identity provider client), D06 (OpenShift/database access) and D07 (real
  procurement endpoint) in `docs/13-decision-register.md`.

## Not verified in this repository

No OpenShift/Kubernetes cluster was available. Verified: Kustomize rendering of all overlays, strict schema validation of
all core resources against Kubernetes 1.31 (kubeconform; `Route` has no published schema and is checked structurally),
and `validate_manifests.py`. Not verified: admission by the `restricted-v2` SCC, Route behaviour under the router, NetworkPolicy
enforcement, HPA scaling, and the `-l` staged apply.
