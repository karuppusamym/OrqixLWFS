#!/usr/bin/env python3
"""Structural validation of the rendered OpenShift manifests (deploy/openshift overlays).

Renders each overlay with `kubectl kustomize` (or `oc kustomize` / `kustomize build`, first one found) and asserts the
platform requirements from docs/build/S1, S5 section 11, S6 section 6 and docs/03-architecture.md: pod hardening, probes on the
management port, rolling-update settings, resource sizing, Route exposure, NetworkPolicy coverage, HPA ceiling against the
database connection budget, migration Job wiring and that no Secret value is rendered.

It complements, but does not replace, schema validation (kubeconform in CI) and a server-side dry run on a cluster.
Dependency: pyyaml. Exit status 0 when all checks pass.
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

import yaml

OVERLAYS = ("dev", "int", "prod")
EXPECTED_KINDS = {
    "ServiceAccount": 1, "ConfigMap": 1, "Service": 1, "Deployment": 1, "PodDisruptionBudget": 1,
    "HorizontalPodAutoscaler": 1, "Job": 1, "Route": 1, "NetworkPolicy": 7,
}
CONNECTIONS_PER_POD = 15
MIGRATION_CONNECTIONS = 5
OPERATIONS_CONNECTIONS = 10
MAX_CONNECTIONS = 100  # S5 section 11 reference value


class Report:
    def __init__(self) -> None:
        self.failures: list[str] = []
        self.count = 0

    def check(self, condition: bool, ok: str, fail: str) -> None:
        self.count += 1
        if condition:
            print(f"  PASS  {ok}")
        else:
            self.failures.append(fail)
            print(f"  FAIL  {fail}")


def render(overlay: Path) -> list[dict]:
    for tool, args in (("kubectl", ["kustomize"]), ("oc", ["kustomize"]), ("kustomize", ["build"])):
        if shutil.which(tool):
            result = subprocess.run([tool, *args, str(overlay)], capture_output=True, text=True, check=False)
            if result.returncode != 0:
                raise SystemExit(f"{tool} failed for {overlay}:\n{result.stderr}")
            return [d for d in yaml.safe_load_all(result.stdout) if d]
    raise SystemExit("none of kubectl, oc or kustomize is installed")


def by_kind(docs: list[dict], kind: str) -> list[dict]:
    return [d for d in docs if d.get("kind") == kind]


def one(docs: list[dict], kind: str, name: str | None = None) -> dict:
    matches = [d for d in by_kind(docs, kind) if name is None or d["metadata"]["name"] == name]
    if len(matches) != 1:
        raise SystemExit(f"expected exactly one {kind} {name or ''}, found {len(matches)}")
    return matches[0]


def selects(selector: dict, labels: dict) -> bool:
    if "matchLabels" not in selector and "matchExpressions" not in selector:
        return all(labels.get(k) == v for k, v in selector.items())  # Service-style selector
    if not all(labels.get(k) == v for k, v in (selector.get("matchLabels") or {}).items()):
        return False
    for expr in selector.get("matchExpressions") or []:
        if expr["operator"] != "In":
            raise SystemExit(f"unsupported selector operator {expr['operator']}")
        if labels.get(expr["key"]) not in expr["values"]:
            return False
    return True


def env_map(container: dict) -> dict:
    return {e["name"]: e for e in container.get("env", [])}


def check_overlay(name: str, docs: list[dict], secret_refs: dict[str, set[str]], report: Report) -> None:
    print(f"\n== overlay {name}")
    kinds = {k: len(by_kind(docs, k)) for k in EXPECTED_KINDS}
    report.check(kinds == EXPECTED_KINDS, "expected resource set rendered", f"{name}: resource counts {kinds} != {EXPECTED_KINDS}")
    report.check(not by_kind(docs, "Secret"), "no Secret rendered (references only)", f"{name}: a Secret is rendered")

    stages = {d["kind"]: d["metadata"].get("labels", {}).get("orqix.io/stage") for d in docs if d["kind"] != "NetworkPolicy"}
    expected_stage = {"ServiceAccount": "prerequisites", "ConfigMap": "prerequisites", "Job": "migration", "Deployment": "app",
                      "Service": "app", "Route": "app", "PodDisruptionBudget": "app", "HorizontalPodAutoscaler": "app"}
    report.check(stages == expected_stage, "orqix.io/stage labels support the staged apply", f"{name}: stage labels {stages}")
    report.check(all(d["metadata"].get("labels", {}).get("orqix.io/stage") == "prerequisites" for d in by_kind(docs, "NetworkPolicy")),
                 "NetworkPolicies belong to stage prerequisites", f"{name}: NetworkPolicy stage label")

    dep = one(docs, "Deployment", "orqix")
    spec = dep["spec"]
    pod = spec["template"]["spec"]
    labels = spec["template"]["metadata"]["labels"]
    container = pod["containers"][0]

    if name == "dev":
        report.check(spec.get("replicas") == 2, "2 replicas", f"{name}: replicas {spec.get('replicas')}")
    else:
        report.check("replicas" not in spec, "replicas left to the HPA", f"{name}: replicas must be removed when an HPA is present")
    strategy = spec["strategy"]
    report.check(strategy["type"] == "RollingUpdate" and str(strategy["rollingUpdate"]["maxUnavailable"]) == "0",
                 "rolling update with maxUnavailable 0", f"{name}: strategy {strategy}")
    report.check(selects(spec["selector"], labels), "selector matches pod labels", f"{name}: selector does not match template labels")
    report.check(pod.get("terminationGracePeriodSeconds") == 60, "terminationGracePeriodSeconds 60", f"{name}: terminationGracePeriodSeconds")
    report.check(pod["securityContext"].get("runAsNonRoot") is True and pod["securityContext"]["seccompProfile"]["type"] == "RuntimeDefault"
                 and "runAsUser" not in pod["securityContext"], "pod: runAsNonRoot, seccomp RuntimeDefault, UID left to the SCC",
                 f"{name}: pod securityContext {pod['securityContext']}")
    sc = container["securityContext"]
    report.check(sc.get("allowPrivilegeEscalation") is False and sc.get("readOnlyRootFilesystem") is True
                 and sc.get("capabilities", {}).get("drop") == ["ALL"] and sc.get("runAsNonRoot") is True,
                 "container: no privilege escalation, read-only root, drop ALL, non-root", f"{name}: container securityContext {sc}")
    res = container["resources"]
    if name == "dev":
        report.check(res["requests"] == {"cpu": "250m", "memory": "768Mi"}, "dev resources reduced", f"{name}: dev resources {res}")
    else:
        report.check(res == {"requests": {"cpu": "500m", "memory": "1Gi"}, "limits": {"cpu": "2", "memory": "2Gi"}},
                     "resources 500m/1Gi requests, 2/2Gi limits", f"{name}: resources {res}")
    ports = {p["name"]: p["containerPort"] for p in container["ports"]}
    report.check(ports == {"http": 8080, "management": 8081}, "container ports 8080/8081", f"{name}: ports {ports}")
    probes_ok = all(
        container[p]["httpGet"]["port"] == "management" and container[p]["httpGet"]["path"] == path
        for p, path in (("startupProbe", "/actuator/health/liveness"), ("livenessProbe", "/actuator/health/liveness"),
                        ("readinessProbe", "/actuator/health/readiness")))
    report.check(probes_ok, "startup/liveness/readiness probes on the management port", f"{name}: probes {[container.get(p) for p in ('startupProbe','livenessProbe','readinessProbe')]}")
    env = env_map(container)
    report.check(env["SPRING_PROFILES_ACTIVE"].get("value") == "prod", "SPRING_PROFILES_ACTIVE=prod", f"{name}: profile")
    volumes = {v["name"]: v for v in pod["volumes"]}
    mounts = {m["mountPath"]: m["name"] for m in container["volumeMounts"]}
    report.check("emptyDir" in volumes.get(mounts.get("/tmp", ""), {}), "/tmp is an emptyDir", f"{name}: /tmp mount")
    secret_vol = volumes.get(mounts.get("/var/run/secrets/orqix", ""), {})
    report.check("secret" in secret_vol and container["volumeMounts"][[m["mountPath"] for m in container["volumeMounts"]].index("/var/run/secrets/orqix")].get("readOnly") is True,
                 "connection secrets mounted read-only at /var/run/secrets/orqix", f"{name}: secret volume {secret_vol}")

    image = container["image"]
    report.check(image != "orqix" and not image.endswith(":latest"), f"overlay sets the image ({image})",
                 f"{name}: image {image} is the bare placeholder or :latest (overlay images entry not applied)")
    if name == "prod":
        report.check("@sha256:" in image, "prod image pinned by digest", f"{name}: image {image} is not pinned by digest")

    config_name = one(docs, "ConfigMap")["metadata"]["name"]
    report.check(container["envFrom"][0]["configMapRef"]["name"] == config_name, "envFrom references the generated ConfigMap", f"{name}: configMapRef")
    refs = {(e["valueFrom"]["secretKeyRef"]["name"], e["valueFrom"]["secretKeyRef"]["key"]) for e in container["env"] if "valueFrom" in e}
    missing = [r for r in refs if r[1] not in secret_refs.get(r[0], set())]
    report.check(not missing, f"all {len(refs)} secretKeyRefs are documented in secrets.example.yaml", f"{name}: undocumented secret refs {missing}")

    data = one(docs, "ConfigMap")["data"]
    report.check(data.get("ORQIX_EGRESS_PLAIN_HTTP_HOSTS", "") == "", "plain-http egress hosts empty (prod would refuse to start)", f"{name}: ORQIX_EGRESS_PLAIN_HTTP_HOSTS={data.get('ORQIX_EGRESS_PLAIN_HTTP_HOSTS')}")
    report.check(bool(data.get("ORQIX_EGRESS_ALLOWED_HOSTS")), "egress allowlist configured", f"{name}: ORQIX_EGRESS_ALLOWED_HOSTS empty")
    report.check(data.get("ORQIX_SECRETS_DIR") == "/var/run/secrets/orqix", "ORQIX_SECRETS_DIR matches the mount", f"{name}: ORQIX_SECRETS_DIR")
    report.check(not any(k.endswith(("_PASSWORD", "_SECRET", "_TOKEN", "_KEY")) for k in data), "no password/secret keys in the ConfigMap", f"{name}: sensitive key in ConfigMap")

    svc = one(docs, "Service", "orqix")
    svc_ports = {p["name"]: p["port"] for p in svc["spec"]["ports"]}
    report.check(svc_ports == {"http": 8080, "management": 8081} and selects(svc["spec"]["selector"], labels), "Service exposes 8080 http and 8081 management to the pods", f"{name}: service {svc['spec']}")

    route = one(docs, "Route", "orqix")
    ann = route["metadata"]["annotations"]
    report.check(route["spec"]["port"]["targetPort"] == "http" and route["spec"]["to"]["name"] == "orqix", "Route targets only the http port of the Service", f"{name}: route port")
    report.check(route["spec"]["tls"] == {"termination": "edge", "insecureEdgeTerminationPolicy": "Redirect"}, "edge TLS with insecure redirect", f"{name}: route tls")
    report.check("haproxy.router.openshift.io/hsts_header" in ann and ann.get("haproxy.router.openshift.io/rate-limit-connections") == "true"
                 and all(k in ann for k in ("haproxy.router.openshift.io/rate-limit-connections.rate-http", "haproxy.router.openshift.io/rate-limit-connections.concurrent-tcp")),
                 "HSTS and rate-limit annotations", f"{name}: route annotations")
    report.check("8081" not in yaml.safe_dump(route["spec"]), "management port not referenced by the Route", f"{name}: route references 8081")

    hpa = one(docs, "HorizontalPodAutoscaler")["spec"]
    report.check(hpa["scaleTargetRef"]["name"] == "orqix" and hpa["minReplicas"] == 2 and hpa["maxReplicas"] == 4, "HPA 2..4 on the Deployment", f"{name}: hpa {hpa}")
    budget = hpa["maxReplicas"] * CONNECTIONS_PER_POD + MIGRATION_CONNECTIONS + OPERATIONS_CONNECTIONS
    report.check(budget <= 0.8 * MAX_CONNECTIONS, f"HPA ceiling fits the connection budget ({budget} <= {0.8 * MAX_CONNECTIONS:.0f} of max_connections {MAX_CONNECTIONS})", f"{name}: connection budget {budget} exceeds 80% of {MAX_CONNECTIONS}")
    report.check(data.get("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE") == str(CONNECTIONS_PER_POD), "pool size 15 per pod", f"{name}: pool size")

    pdb = one(docs, "PodDisruptionBudget")["spec"]
    report.check(pdb["minAvailable"] == 1 and selects(pdb["selector"], labels), "PDB minAvailable 1 selects the pods", f"{name}: pdb {pdb}")

    job = one(docs, "Job", "orqix-migrate")
    jc = job["spec"]["template"]["spec"]["containers"][0]
    jenv = env_map(jc)
    report.check(jc["image"] == container["image"], "migration Job uses the same image as the pods", f"{name}: job image {jc['image']} != {container['image']}")
    report.check(jenv["SPRING_PROFILES_ACTIVE"].get("value") == "migrate", "Job runs SPRING_PROFILES_ACTIVE=migrate", f"{name}: job profile")
    jsc = jc["securityContext"]
    report.check(jsc.get("readOnlyRootFilesystem") is True and jsc.get("allowPrivilegeEscalation") is False and jsc["capabilities"]["drop"] == ["ALL"]
                 and job["spec"]["backoffLimit"] == 0 and job["spec"]["template"]["spec"]["restartPolicy"] == "Never",
                 "Job hardened, never retried automatically", f"{name}: job hardening")
    report.check(jc["envFrom"][0]["configMapRef"]["name"] == config_name, "Job reads the same generated ConfigMap", f"{name}: job configMapRef")

    policies = {p["metadata"]["name"]: p["spec"] for p in by_kind(docs, "NetworkPolicy")}
    deny = policies["orqix-default-deny"]
    report.check(set(deny["policyTypes"]) == {"Ingress", "Egress"} and not deny.get("ingress") and not deny.get("egress"), "default deny ingress and egress", f"{name}: default-deny")
    router = policies["orqix-allow-router-ingress"]["ingress"][0]
    report.check([p["port"] for p in router["ports"]] == [8080], "router ingress limited to port 8080", f"{name}: router ingress {router}")
    mon = policies["orqix-allow-monitoring-ingress"]["ingress"][0]
    report.check([p["port"] for p in mon["ports"]] == [8081] and len(mon["from"]) == 2, "monitoring ingress limited to port 8081", f"{name}: monitoring ingress {mon}")
    dns_ports = {p["port"] for p in policies["orqix-allow-dns-egress"]["egress"][0]["ports"]}
    report.check({53, 5353} <= dns_ports, "DNS egress", f"{name}: dns ports {dns_ports}")
    report.check([p["port"] for p in policies["orqix-allow-postgres-egress"]["egress"][0]["ports"]] == [5432], "PostgreSQL egress on 5432", f"{name}: postgres egress")
    report.check([p["port"] for p in policies["orqix-allow-idp-egress"]["egress"][0]["ports"]] == [443], "IdP egress on 443", f"{name}: idp egress")
    report.check([p["port"] for p in policies["orqix-allow-connection-egress"]["egress"][0]["ports"]] == [443], "connection egress on 443", f"{name}: connection egress")
    job_labels = job["spec"]["template"]["metadata"]["labels"]
    report.check(selects(policies["orqix-allow-postgres-egress"]["podSelector"], job_labels) and selects(policies["orqix-allow-dns-egress"]["podSelector"], job_labels)
                 and not selects(policies["orqix-allow-idp-egress"]["podSelector"], job_labels),
                 "migration Job may reach DNS and PostgreSQL only", f"{name}: job network policy coverage")

    namespaces = {d["metadata"].get("namespace") for d in docs}
    report.check(namespaces == {f"orqix-{name}"}, f"all resources in namespace orqix-{name}", f"{name}: namespaces {namespaces}")
    placeholders = sum(yaml.safe_dump(d).count("REPLACE") for d in docs)
    print(f"  INFO  {placeholders} REPLACE placeholders remain to be completed by the environment owner")


SPLIT_OVERLAY = "prod-split"
API_CONNECTIONS_PER_POD = 10


def check_split(docs: list[dict], secret_refs: dict[str, set[str]], report: Report) -> None:
    """docs/adr/0004 step 1: prod plus the split-roles component (API pods + worker pods)."""
    name = SPLIT_OVERLAY
    print(f"\n== overlay {name}")
    expected = dict(EXPECTED_KINDS, Deployment=2, PodDisruptionBudget=2)
    kinds = {k: len(by_kind(docs, k)) for k in expected}
    report.check(kinds == expected, "expected resource set rendered (API and worker Deployments and PDBs)", f"{name}: resource counts {kinds}")
    report.check(not by_kind(docs, "Secret"), "no Secret rendered", f"{name}: a Secret is rendered")

    api, worker = one(docs, "Deployment", "orqix"), one(docs, "Deployment", "orqix-worker")
    api_c, worker_c = api["spec"]["template"]["spec"]["containers"][0], worker["spec"]["template"]["spec"]["containers"][0]
    api_labels, worker_labels = api["spec"]["template"]["metadata"]["labels"], worker["spec"]["template"]["metadata"]["labels"]
    api_env, worker_env = env_map(api_c), env_map(worker_c)
    report.check(api_env["SPRING_PROFILES_ACTIVE"].get("value") == "prod,api", "API pods run profiles prod,api", f"{name}: API profile {api_env['SPRING_PROFILES_ACTIVE']}")
    report.check(worker_env["SPRING_PROFILES_ACTIVE"].get("value") == "prod,worker", "worker pods run profiles prod,worker", f"{name}: worker profile {worker_env['SPRING_PROFILES_ACTIVE']}")
    report.check(api_env.get("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", {}).get("value") == str(API_CONNECTIONS_PER_POD),
                 f"API pool size {API_CONNECTIONS_PER_POD}", f"{name}: API pool size")
    report.check("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE" not in worker_env, "worker pool size from the ConfigMap (15)", f"{name}: worker pool override")
    report.check(worker_labels.get("app.kubernetes.io/component") == "worker" and selects(worker["spec"]["selector"], worker_labels)
                 and not selects(worker["spec"]["selector"], api_labels) and not selects(api["spec"]["selector"], worker_labels),
                 "API and worker selectors are disjoint", f"{name}: deployment selectors overlap")
    report.check("replicas" not in api["spec"] and worker["spec"].get("replicas") == 2, "API replicas left to the HPA, 2 workers", f"{name}: replicas")
    report.check(len({api_c["image"], worker_c["image"], one(docs, "Job")["spec"]["template"]["spec"]["containers"][0]["image"]}) == 1
                 and "@sha256:" in worker_c["image"], "API, worker and migration Job use the same digest-pinned image", f"{name}: images differ")
    same = ("securityContext", "resources", "startupProbe", "livenessProbe", "readinessProbe", "volumeMounts", "envFrom", "lifecycle")
    report.check(all(api_c.get(k) == worker_c.get(k) for k in same), "worker container hardening, probes, resources and mounts match the API pods",
                 f"{name}: worker differs in {[k for k in same if api_c.get(k) != worker_c.get(k)]}")
    pod_same = ("securityContext", "volumes", "terminationGracePeriodSeconds", "serviceAccountName", "automountServiceAccountToken")
    report.check(all(api["spec"]["template"]["spec"].get(k) == worker["spec"]["template"]["spec"].get(k) for k in pod_same),
                 "worker pod security, volumes and grace period match the API pods", f"{name}: worker pod spec differs")
    report.check({p["name"] for p in worker_c["ports"]} == {"management"}, "worker declares only the management port", f"{name}: worker ports")
    refs = {(e["valueFrom"]["secretKeyRef"]["name"], e["valueFrom"]["secretKeyRef"]["key"]) for e in worker_c["env"] if "valueFrom" in e}
    report.check(refs == {(e["valueFrom"]["secretKeyRef"]["name"], e["valueFrom"]["secretKeyRef"]["key"]) for e in api_c["env"] if "valueFrom" in e}
                 and all(r[1] in secret_refs.get(r[0], set()) for r in refs), "worker reads the same documented Secrets", f"{name}: worker secret refs {refs}")

    svc, route = one(docs, "Service", "orqix"), one(docs, "Route", "orqix")
    report.check(selects(svc["spec"]["selector"], api_labels) and not selects(svc["spec"]["selector"], worker_labels)
                 and route["spec"]["to"]["name"] == "orqix", "Service and Route reach the API pods only", f"{name}: Service selects workers")
    pdbs = {p["metadata"]["name"]: p["spec"] for p in by_kind(docs, "PodDisruptionBudget")}
    report.check(selects(pdbs["orqix"]["selector"], api_labels) and not selects(pdbs["orqix"]["selector"], worker_labels)
                 and selects(pdbs["orqix-worker"]["selector"], worker_labels) and pdbs["orqix-worker"]["minAvailable"] == 1,
                 "one PDB per role, minAvailable 1", f"{name}: pdbs {pdbs}")

    policies = {p["metadata"]["name"]: p["spec"]["podSelector"] for p in by_kind(docs, "NetworkPolicy")}
    worker_allowed = {"orqix-default-deny", "orqix-allow-monitoring-ingress", "orqix-allow-dns-egress", "orqix-allow-postgres-egress",
                      "orqix-allow-connection-egress"}
    report.check({n for n, s in policies.items() if selects(s, worker_labels)} == worker_allowed,
                 "workers: default deny, monitoring ingress, DNS, PostgreSQL and connection egress only (no router ingress, no IdP)",
                 f"{name}: worker policies {sorted(n for n, s in policies.items() if selects(s, worker_labels))}")
    report.check(all(selects(s, api_labels) for n, s in policies.items()), "API pods keep every policy", f"{name}: API pods lost a policy")

    hpa = one(docs, "HorizontalPodAutoscaler")["spec"]
    budget = hpa["maxReplicas"] * API_CONNECTIONS_PER_POD + worker["spec"]["replicas"] * CONNECTIONS_PER_POD + MIGRATION_CONNECTIONS + OPERATIONS_CONNECTIONS
    report.check(hpa["scaleTargetRef"]["name"] == "orqix" and hpa["maxReplicas"] == 3 and budget <= 0.8 * MAX_CONNECTIONS,
                 f"API HPA ..3 plus 2 workers fits the connection budget ({budget} <= {0.8 * MAX_CONNECTIONS:.0f})", f"{name}: connection budget {budget}")
    namespaces = {d["metadata"].get("namespace") for d in docs}
    report.check(namespaces == {"orqix-prod"}, "all resources in namespace orqix-prod", f"{name}: namespaces {namespaces}")


def main() -> int:
    root = Path(__file__).resolve().parents[2] / "deploy" / "openshift"
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=root, help="deploy/openshift directory")
    args = parser.parse_args()

    secrets: dict[str, set[str]] = {}
    for doc in yaml.safe_load_all((args.root / "base" / "secrets.example.yaml").read_text(encoding="utf-8")):
        if doc and doc.get("kind") == "Secret":
            secrets[doc["metadata"]["name"]] = set(doc.get("stringData", {}))

    report = Report()
    for overlay in OVERLAYS:
        check_overlay(overlay, render(args.root / "overlays" / overlay), secrets, report)
    check_split(render(args.root / "overlays" / SPLIT_OVERLAY), secrets, report)
    print()
    if report.failures:
        print(f"RESULT: FAIL - {len(report.failures)} of {report.count} checks failed")
        return 1
    print(f"RESULT: PASS - {report.count} checks across {len(OVERLAYS) + 1} overlays")
    return 0


if __name__ == "__main__":
    sys.exit(main())
