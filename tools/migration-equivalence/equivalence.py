"""Golden-scenario target acceptance and deterministic evidence packs. No source code execution."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import sys
import time
import uuid
import zipfile
from urllib.parse import urlparse

VERSION = "orqix-migration-equivalence-0.1"
ROOT = Path(__file__).resolve().parents[2]
CHECKS = {"startStatus", "lifecycle", "outcome", "approvalNodes", "succeededEffects", "matchedStubOrders"}


def canonical(value):
    return (json.dumps(value, sort_keys=True, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def load_suite(path):
    suite = json.loads(path.read_text(encoding="utf-8"))
    if suite.get("format") != "orqix-migration-golden-0.1" or not suite.get("cases"):
        raise ValueError("Expected a nonempty orqix-migration-golden-0.1 suite")
    ids = set()
    for case in suite["cases"]:
        if not isinstance(case.get("id"), str) or not re.fullmatch(r"[a-z][a-z0-9-]{0,99}", case["id"]) or case["id"] in ids:
            raise ValueError("Scenario IDs must be unique strings")
        ids.add(case["id"])
        if not case.get("expected") or not set(case["expected"]).issubset(CHECKS):
            raise ValueError("Expected observations contain unsupported checks")
    return suite


def compare(suite, observations):
    seen = {}
    for observation in observations.get("cases", []):
        if observation.get("id") in seen:
            raise ValueError("Duplicate observation ID")
        seen[observation.get("id")] = observation
    results = []
    for case in suite["cases"]:
        observed = seen.get(case["id"], {})
        checks = [{"field": key, "expected": expected, "actual": observed.get(key),
                   "passed": key in observed and canonical(observed[key]) == canonical(expected)}
                  for key, expected in sorted(case["expected"].items())]
        results.append({"id": case["id"], "passed": all(c["passed"] for c in checks), "checks": checks})
    unexpected = sorted(str(key) for key in seen if key not in {c["id"] for c in suite["cases"]})
    return {"harnessVersion": VERSION, "executionMode": observations.get("executionMode", "providedObservations"),
            "sourceValidation": suite["source"].get("validationStatus", "unverified"),
            "sourceOwnerAcceptance": suite["source"].get("ownerAcceptance", "pending"),
            "equivalenceClaim": "Target observations compared with declared expectations; source semantic equivalence is not established.",
            "passed": all(r["passed"] for r in results) and not unexpected,
            "unexpectedScenarioIds": unexpected, "results": results}


def write_pack(destination, suite, observations, result, artifacts):
    """Same evidence bytes produce identical ZIP bytes, including timestamps and ordering."""
    files = {"suite.json": canonical(suite), "observations.json": canonical(observations),
             "results.json": canonical(result), **artifacts}
    manifest = {"format": "orqix-migration-evidence-0.1", "harnessVersion": VERSION,
                "files": {name: {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
                          for name, data in sorted(files.items())}}
    files["manifest.json"] = canonical(manifest)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, data in sorted(files.items()):
            if name.startswith("/") or ".." in Path(name).parts:
                raise ValueError("Unsafe evidence archive entry")
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    return hashlib.sha256(destination.read_bytes()).hexdigest()


def response_json(response, expected):
    if response.status_code not in expected:
        # Do not print response data, credentials, or the authenticated redirect URL.
        raise RuntimeError(f"API returned {response.status_code}; expected {expected}")
    return response.json()


def live_observations(suite, suite_path, timeout):
    """Uses dev realm accounts and the existing same-origin OIDC/CSRF client; only local stack."""
    sys.path.insert(0, str(ROOT / "tools" / "api-e2e"))
    from orqix_client import Client
    import requests
    designer, publisher, requester = (Client(name) for name in ("dana.designer", "paul.publisher", "rhea.requester"))
    ws = next(w["id"] for w in response_json(requester.get("/me"), {200})["workspaces"] if w["key"] == "purchasing")
    admin = Client("wade.admin")
    operator = Client("olga.operator")
    binding = response_json(admin.get(f"/workspaces/{ws}/connections/procurement"), {200}).get("binding") or {}
    endpoint = urlparse(binding.get("baseUrl", ""))
    stub_endpoint = (endpoint.hostname == "procurement-stub" and endpoint.port == 8090) or (endpoint.hostname in {"localhost", "127.0.0.1"} and endpoint.port == 8095)
    if not binding.get("enabled") or endpoint.scheme != "http" or not stub_endpoint:
        raise ValueError("Live harness requires procurement bound to the local development stub")
    source_path = (suite_path.parent / suite["source"]["file"]).resolve()
    template_path = (suite_path.parent / suite["targetTemplate"]).resolve()
    template = json.loads(template_path.read_text(encoding="utf-8"))
    if suite["source"]["format"] != "bpmn":
        raise ValueError("Live automatic import currently supports reviewed BPMN fixtures only")
    job = response_json(designer.post(f"/workspaces/{ws}/migrations", {"fileName": source_path.name,
        "sourceFormat": "bpmn", "content": source_path.read_text(encoding="utf-8")}), {201})
    service = next(node["config"] for node in template["nodes"] if node["id"] == "procure")
    conversion = {"workflowKey": "golden-" + uuid.uuid4().hex[:12], "name": "Golden scenario migration",
        "inputSchema": template["inputSchema"], "acknowledgeInfo": True, "acknowledgeReview": True,
        "groupMappings": {"source-managers": "managers", "source-finance": "finance-approvers"},
        "serviceTaskMappings": {"procure": service}}
    target = response_json(designer.post(f"/workspaces/{ws}/migrations/{job['id']}/convert", conversion), {201})
    workflow = target["workflow"]["id"]
    release = response_json(publisher.post(f"/workspaces/{ws}/workflows/{workflow}/publish",
        {"draftRevision": target["draft"]["revision"], "acknowledgeWarnings": True}, idem=True), {201})
    final_job = response_json(designer.get(f"/workspaces/{ws}/migrations/{job['id']}"), {200})
    artifacts = {"provenance/migration-report.json": canonical(final_job),
                 "provenance/stub-binding.json": canonical({key: binding.get(key) for key in ("connectionKey", "baseUrl", "enabled", "revision")}),
                 "provenance/conversion-request.json": canonical(conversion),
                 "provenance/target-draft.json": canonical(target["draft"]),
                 "provenance/target-release.json": canonical(release)}
    clients = {"rhea.requester": requester}
    cases = []
    for case in suite["cases"]:
        start = requester.post(f"/workspaces/{ws}/runs", {"workflowId": workflow, "input": case["input"]}, idem=True)
        observed = {"id": case["id"], "startStatus": start.status_code, "approvalNodes": [], "succeededEffects": 0, "matchedStubOrders": 0}
        if start.status_code != 201:
            cases.append(observed)
            continue
        run_id = start.json()["id"]
        deadline = time.monotonic() + timeout
        for decision in case.get("decisions", []):
            actor = decision["actor"]
            if actor not in {"mani.manager", "fin.finance"}:
                raise ValueError("Fixture actor must be a supported local test approver")
            if actor not in clients:
                clients[actor] = Client(actor)
            client = clients[actor]
            task = None
            while time.monotonic() < deadline:
                page = response_json(client.get(f"/workspaces/{ws}/tasks", view="claimable", limit=100), {200})
                task = next((t for t in page["items"] if t["runId"] == run_id and t["nodeId"] == decision["nodeId"]), None)
                if task:
                    break
                time.sleep(0.25)
            if not task:
                observed["error"] = "Expected approval task was not reached before deadline"
                break
            claimed = response_json(client.post(f"/workspaces/{ws}/tasks/{task['id']}/claim", {"expectedVersion": task["version"]}), {200})
            body = {"expectedVersion": claimed["version"], "action": decision["action"]}
            if decision.get("reason"):
                body["reason"] = decision["reason"]
            response_json(client.post(f"/workspaces/{ws}/tasks/{task['id']}/decision", body, idem=True), {201})
            observed["approvalNodes"].append(task["nodeId"])
        while True:
            run = response_json(operator.get(f"/workspaces/{ws}/runs/{run_id}"), {200})
            if run["lifecycle"] in {"completed", "cancelled", "needsAttention"} or time.monotonic() >= deadline:
                break
            time.sleep(0.25)
        observed.update({"runId": run_id, "lifecycle": run["lifecycle"], "outcome": run.get("outcome"),
                         "succeededEffects": sum(effect["state"] == "succeeded" for effect in run.get("effects", []))})
        references = {effect.get("remoteReference") for effect in run.get("effects", []) if effect["state"] == "succeeded"}
        if references:
            orders = response_json(requests.get("http://localhost:8095/__orders", timeout=10), {200})["orders"]
            matched = [order for order in orders if order["id"] in references]
            observed["matchedStubOrders"] = len(matched)
            artifacts[f"stub/{case['id']}.json"] = canonical({"orders": matched})
        cases.append(observed)
        artifacts[f"runs/{case['id']}.json"] = canonical(run)
    return {"format": "orqix-migration-observations-0.1", "executionMode": "liveLocalStubbedEffects", "cases": cases}, artifacts


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", type=Path, default=Path(__file__).with_name("purchase-suite.json"))
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--observations", type=Path, help="Compare provided evidence; this does not execute a workflow")
    mode.add_argument("--live", action="store_true", help="Import/publish/execute against localhost development stack")
    parser.add_argument("--out", type=Path, default=ROOT / "evidence" / "migration-equivalence.zip")
    parser.add_argument("--timeout", type=int, default=60)
    args = parser.parse_args(argv)
    suite = load_suite(args.suite)
    source_path = (args.suite.parent / suite["source"]["file"]).resolve()
    artifacts = {"source/" + source_path.name: source_path.read_bytes()}
    if args.live:
        try:
            observations, live_artifacts = live_observations(suite, args.suite, args.timeout)
            artifacts.update(live_artifacts)
        except (RuntimeError, ValueError, KeyError, StopIteration) as error:
            # Keep infrastructure failure evidence without exporting redirect URLs or sensitive response bodies.
            observations = {"format": "orqix-migration-observations-0.1", "executionMode": "liveLocalStubbedEffects",
                            "executionFailure": type(error).__name__, "cases": []}
    else:
        observations = json.loads(args.observations.read_text(encoding="utf-8"))
    result = compare(suite, observations)
    if observations.get("executionFailure"):
        result["executionFailure"] = observations["executionFailure"]
    checksum = write_pack(args.out, suite, observations, result, artifacts)
    print(json.dumps({"passed": result["passed"], "executionMode": result["executionMode"],
                      "sourceValidation": result["sourceValidation"], "evidence": str(args.out), "sha256": checksum}))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
