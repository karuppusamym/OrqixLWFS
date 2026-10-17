"""Validate and execute explicitly synthetic, reviewed Pega migration scenarios.

The archive XML is descriptive fixture data, not a native Pega export grammar.
This runner tests archive -> reviewed mapping -> published target -> actual runs.
It never evaluates Pega code or claims source-runtime equivalence.
"""
import argparse
import base64
import copy
from datetime import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
import time
from urllib.parse import urlsplit
import uuid
import zipfile

from equivalence import ROOT, canonical, write_pack

FORMAT = "orqix-pega-synthetic-suite-0.1"
DEFAULT_SUITE = ROOT / "examples/migration/pega-synthetic/suite.json"


def _unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON member")
        result[key] = value
    return result


def read_json(path):
    if path.stat().st_size > 1_048_576:
        raise ValueError("Fixture JSON exceeds 1 MiB")
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_unique_pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Non-finite JSON number")))


def confined(root, name):
    if not isinstance(name, str) or "\\" in name:
        raise ValueError("Expected a relative fixture path")
    path = (root / name).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file():
        raise ValueError("Fixture path escapes its suite directory or is missing")
    return path


def archive_names(data):
    if len(data) > 1_048_576:
        raise ValueError("Compressed fixture archive exceeds 1 MiB")
    names, expanded = set(), 0
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        if not entries or len(entries) > 200:
            raise ValueError("Expected 1 to 200 archive entries")
        for entry in entries:
            name = entry.filename.replace("\\", "/")
            if len(name) > 500 or name.startswith("/") or ":" in name or ".." in name.split("/") or name in names:
                raise ValueError("Unsafe or duplicate archive path")
            names.add(name)
            expanded += entry.file_size
            if entry.file_size > 1_048_576 or expanded > 4_194_304:
                raise ValueError("Expanded archive exceeds limits")
            if entry.flag_bits & 1:
                raise ValueError("Encrypted fixture entries are unsupported")
            if not entry.is_dir():
                # Reading also verifies CRC; nothing is extracted to disk.
                archive.read(entry)
        return {entry.filename.replace("\\", "/") for entry in entries if not entry.is_dir()}


def load_fixtures(path):
    from jsonschema import Draft202012Validator
    suite = read_json(path)
    if suite.get("format") != FORMAT or suite.get("sourceValidation") != "syntheticFixture":
        raise ValueError("Only explicitly synthetic Pega fixture suites are supported")
    if not str(suite.get("sourceVersion", "")).startswith("synthetic-"):
        raise ValueError("Synthetic source version must be explicit")
    entries = suite.get("workflows")
    if not isinstance(entries, list) or not 1 <= len(entries) <= 10:
        raise ValueError("Expected 1 to 10 synthetic workflows")
    validator = Draft202012Validator(read_json(ROOT / "contracts/workflow-v0.schema.json"))
    loaded, artifacts, keys = {}, {}, set()
    for entry in entries:
        identity = entry.get("id")
        if not isinstance(identity, str) or not re.fullmatch(r"[a-z][a-z0-9-]{0,30}", identity) or identity in loaded:
            raise ValueError("Workflow IDs must be unique bounded identifiers")
        deps = entry.get("dependencies", [])
        if not isinstance(deps, list) or len(deps) != len(set(deps)) or any(dep not in loaded for dep in deps):
            raise ValueError("Dependencies must name earlier workflows exactly once")
        files = {kind: confined(path.parent, entry[kind]) for kind in ("archive", "definition", "fileMappings")}
        for kind, field in (("archive", "archiveSha256"), ("definition", "definitionSha256"), ("fileMappings", "fileMappingsSha256")):
            if entry.get(field) != hashlib.sha256(files[kind].read_bytes()).hexdigest():
                raise ValueError("Fixture checksum differs from its declared suite provenance")
        data = files["archive"].read_bytes()
        names = archive_names(data)
        definition, mappings = read_json(files["definition"]), read_json(files["fileMappings"])
        validator.validate(definition)
        key = definition["workflowKey"]
        if key in keys:
            raise ValueError("Target workflow keys must be unique")
        keys.add(key)
        if definition.get("dependencies", {}).get("connections"):
            raise ValueError("Synthetic runner forbids external connection dependencies")
        nodes = {node["id"] for node in definition["nodes"]}
        if set(mappings) != names:
            raise ValueError("Every archive file needs exactly one explicit disposition")
        mapped = set()
        for mapping in mappings.values():
            if not isinstance(mapping.get("explanation"), str) or not mapping["explanation"].strip():
                raise ValueError("Every disposition needs an explanation")
            targets = mapping.get("targetNodeIds", [])
            if not isinstance(targets, list) or any(not isinstance(target, str) or target not in nodes for target in targets):
                raise ValueError("Unknown target provenance")
            if mapping.get("disposition") == "mapped" and targets:
                mapped.update(targets)
            elif mapping.get("disposition") != "excluded" or targets:
                raise ValueError("Mapped files need targets; excluded files cannot claim targets")
        if mapped != nodes:
            raise ValueError("Every target node needs reviewed source provenance")
        dep_keys = {loaded[dep]["definition"]["workflowKey"] for dep in deps}
        called_keys = {node["config"]["workflowRef"] for node in definition["nodes"] if node["type"] == "startWorkflow"}
        if called_keys != dep_keys:
            raise ValueError("Declared dependencies must exactly cover called child workflows")
        loaded[identity] = {"definition": definition, "mappings": mappings, "archive": data,
                            "archiveHash": hashlib.sha256(data).hexdigest(), "dependencies": deps}
        for kind, file in files.items():
            artifacts[f"fixtures/{identity}/{kind}/{file.name}"] = file.read_bytes()
    if set(loaded) != {"small", "medium", "complex"}:
        raise ValueError("Acceptance requires small, medium and complex workflows")
    artifacts["fixtures/suite.json"] = path.read_bytes()
    return suite, loaded, artifacts


def local_url(value):
    parsed = urlsplit(value)
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"} or parsed.path not in {"", "/"} or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("Live synthetic acceptance requires an HTTP localhost base URL")
    return value.rstrip("/")


def require(response, status):
    if response.status_code != status:
        raise RuntimeError(f"API returned HTTP {response.status_code}; expected {status}")
    return response.json()


class Acceptance:
    def __init__(self, clients, ws, artifacts, timeout=100):
        self.clients, self.ws, self.artifacts, self.timeout = clients, ws, artifacts, timeout
        self.checks, self.imported, self.owned_runs = [], {}, []
        self.scenarios = []

    def check(self, name, actual, expected=True):
        passed = canonical(actual) == canonical(expected)
        self.checks.append({"name": name, "passed": passed, "actual": actual, "expected": expected})
        print(f"[{'PASS' if passed else 'FAIL'}] {name}", flush=True)
        return passed

    def path(self, suffix):
        return f"/workspaces/{self.ws}/{suffix}"

    def import_all(self, suite, fixtures):
        designer, publisher = self.clients["designer"], self.clients["publisher"]
        suffix = uuid.uuid4().hex[:10]
        keys = {fixture["definition"]["workflowKey"]: f"pega-{identity}-{suffix}" for identity, fixture in fixtures.items()}
        for identity, fixture in fixtures.items():
            archive_upload = {"fileName": f"synthetic-{identity}.zip", "sourceFormat": "pegaArchive", "content": base64.b64encode(fixture["archive"]).decode("ascii")}
            if identity == "small":
                self.check("requester cannot upload migration source", self.clients["requester"].post(self.path("migrations"), archive_upload).status_code, 403)
            archive = require(designer.post(self.path("migrations"), archive_upload), 201)
            self.check(f"{identity}: archive remains blocked pending reviewed mapping", archive["report"]["publishable"], False)
            self.check(f"{identity}: outsider cannot read source inventory", self.clients["outsider"].get(self.path(f"migrations/{archive['id']}")).status_code, 404)
            request = {"workflowKey": keys[fixture["definition"]["workflowKey"]], "name": f"Synthetic Pega {identity}", "acknowledgeInfo": True, "acknowledgeReview": True}
            self.check(f"{identity}: native inventory cannot create executable draft", designer.post(self.path(f"migrations/{archive['id']}/convert"), request).status_code, 422)
            definition = copy.deepcopy(fixture["definition"])
            definition["workflowKey"] = request["workflowKey"]
            for node in definition["nodes"]:
                if node["type"] == "startWorkflow":
                    node["config"]["workflowRef"] = keys[node["config"]["workflowRef"]]
            package = {"format": "orqix-pega-mapping-0.1", "archiveJobId": archive["id"], "sourceArchiveHash": fixture["archiveHash"],
                       "sourceVersion": suite["sourceVersion"], "definition": definition, "fileMappings": fixture["mappings"]}
            upload = {"fileName": f"synthetic-{identity}-reviewed.json", "sourceFormat": "pegaMapping", "content": json.dumps(package)}
            if identity == "small":
                tampered = copy.deepcopy(package)
                tampered["sourceArchiveHash"] = "0" * 64
                self.check("wrong archive checksum is refused", designer.post(self.path("migrations"), {**upload, "content": json.dumps(tampered)}).status_code, 422)
                incomplete = copy.deepcopy(package)
                incomplete["fileMappings"].pop(next(iter(incomplete["fileMappings"])))
                incomplete_job = require(designer.post(self.path("migrations"), {**upload, "content": json.dumps(incomplete)}), 201)
                self.check("missing source disposition cannot be acknowledged away", designer.post(self.path(f"migrations/{incomplete_job['id']}/convert"), request).status_code, 422)
                invalid = copy.deepcopy(package)
                invalid["definition"]["transitions"] = [transition for transition in invalid["definition"]["transitions"] if transition["on"] != "rejected"]
                invalid_job = require(designer.post(self.path("migrations"), {**upload, "content": json.dumps(invalid)}), 201)
                self.check("reviewed mapping cannot bypass canonical compiler blockers", designer.post(self.path(f"migrations/{invalid_job['id']}/convert"), request).status_code, 422)
            mapping = require(designer.post(self.path("migrations"), upload), 201)
            self.check(f"{identity}: mapping still requires acknowledgement", mapping["report"]["publishable"], False)
            self.check(f"{identity}: missing acknowledgement blocks conversion", designer.post(self.path(f"migrations/{mapping['id']}/convert"), {**request, "acknowledgeInfo": False}).status_code, 422)
            self.check(f"{identity}: requester cannot convert migration", self.clients["requester"].post(self.path(f"migrations/{mapping['id']}/convert"), request).status_code, 403)
            target = require(designer.post(self.path(f"migrations/{mapping['id']}/convert"), request), 201)
            self.check(f"{identity}: conversion does not publish implicitly", target["workflow"].get("activeRelease") is None)
            repeated = require(designer.post(self.path(f"migrations/{mapping['id']}/convert"), request), 201)
            self.check(f"{identity}: conversion replay reuses original draft", repeated["workflow"]["id"], target["workflow"]["id"])
            release = require(publisher.post(self.path(f"workflows/{target['workflow']['id']}/publish"),
                {"draftRevision": target["draft"]["revision"], "acknowledgeWarnings": True}, idem=True), 201)
            self.imported[identity] = {"workflow": target["workflow"]["id"], "release": release}
            if fixture["dependencies"]:
                pins = release["manifest"]["workflows"]
                self.check(f"{identity}: dependency pins the imported child", [pin["releaseId"] for pin in pins],
                           [self.imported[dep]["release"]["id"] for dep in fixture["dependencies"]])
            for name, artifact in {"archive-report": archive, "mapping-package": package, "mapping-report": mapping,
                                   "conversion-request": request, "target": target, "release": release}.items():
                self.artifacts[f"provenance/{identity}/{name}.json"] = canonical(artifact)

    def start(self, identity, label, extra=None, status=201):
        input_data = {"title": label, "purpose": "Synthetic reviewed migration acceptance", "costCenter": "OPS", "amount": 1800, **(extra or {})}
        response = self.clients["requester"].post(self.path("runs"), {"workflowId": self.imported[identity]["workflow"], "input": input_data}, idem=True)
        require(response, status)
        self.check(f"{label}: admission status", response.status_code, status)
        self.scenarios.append(label)
        if status != 201:
            return None
        run = response.json()
        self.owned_runs.append(run["id"])
        return run["id"]

    def detail(self, run):
        return require(self.clients["operator"].get(self.path(f"runs/{run}")), 200)

    def wait(self, run, predicate):
        deadline = time.monotonic() + self.timeout
        while True:
            detail = self.detail(run)
            if predicate(detail):
                return detail
            if detail["lifecycle"] in {"completed", "cancelled", "needsAttention"} or time.monotonic() >= deadline:
                raise RuntimeError("Expected run state was not reached before the acceptance deadline")
            time.sleep(0.25)

    def decide(self, run, node, action="approve"):
        cursor, task = None, None
        for _ in range(100):
            page = require(self.clients["manager"].get(self.path("tasks"), view="claimable", limit=100, **({"cursor": cursor} if cursor else {})), 200)
            task = next((item for item in page["items"] if item["runId"] == run and item["nodeId"] == node), None)
            if task or not page.get("nextCursor"):
                break
            if page["nextCursor"] == cursor:
                raise RuntimeError("Task pagination did not advance")
            cursor = page["nextCursor"]
        if not task:
            raise RuntimeError("Expected migrated approval task is not claimable")
        claimed = require(self.clients["manager"].post(self.path(f"tasks/{task['id']}/claim"), {"expectedVersion": task["version"]}), 200)
        body = {"expectedVersion": claimed["version"], "action": action}
        if action == "reject":
            body["reason"] = "Synthetic reviewed rejection scenario"
        require(self.clients["manager"].post(self.path(f"tasks/{task['id']}/decision"), body, idem=True), 201)

    def finish(self, run, label, outcome):
        detail = self.wait(run, lambda value: value["lifecycle"] in {"completed", "cancelled", "needsAttention"})
        self.check(f"{label}: terminal lifecycle", detail["lifecycle"], "completed")
        self.check(f"{label}: business outcome", detail.get("outcome"), outcome)
        self.check(f"{label}: no invented external effects", len(detail.get("effects", [])), 0)
        return detail

    def cancel(self, run):
        detail = self.detail(run)
        return require(self.clients["operator"].post(self.path(f"runs/{run}/cancel"),
            {"expectedVersion": detail["version"], "reason": "Synthetic acceptance cancellation"}, idem=True), 200)

    def execute(self):
        for action, outcome in (("approve", "Accepted"), ("reject", "Rejected")):
            label = f"small-{action}"
            run = self.start("small", label)
            self.decide(run, "review", action)
            self.finish(run, label, outcome)
        self.start("small", "small-invalid-input", {"amount": 0}, 422)
        for label, actions, outcome in (("medium-rework", ["reject", "approve"], "Accepted"),
                                         ("medium-exhaustion", ["reject"] * 3, "Rejected")):
            run = self.start("medium", label)
            for index, action in enumerate(actions):
                parent = self.wait(run, lambda value: len(value.get("children", [])) > index)
                child = parent["children"][index]
                self.check(f"{label}: child {index + 1} uses pinned migrated release", child["release"]["id"], self.imported["small"]["release"]["id"])
                self.decide(child["id"], "review", action)
            done = self.finish(run, label, outcome)
            self.check(f"{label}: bounded iteration result", done["output"], {"iterations": len(actions), "limitReached": outcome == "Rejected"})
            self.check(f"{label}: no extra child", len(done["children"]), len(actions))
        pending = {}
        for label in ("complex-accepted", "complex-mixed", "complex-cancel"):
            run = self.start("complex", label, {"items": ["First item", "Second item"]})
            initial = self.detail(run)
            self.artifacts[f"progress/{label}/parallel-open.json"] = canonical(initial)
            self.check(f"{label}: both approvals are open", sorted(step["nodeId"] for step in initial["currentSteps"]), ["one", "two"])
            self.decide(run, "one")
            middle = self.detail(run)
            self.check(f"{label}: join waits for other approval", [step["nodeId"] for step in middle["currentSteps"]], ["two"])
            self.decide(run, "two")
            waiting = self.wait(run, lambda value: any(step["kind"] == "wait" for step in value["currentSteps"]))
            self.check(f"{label}: durable wait has a deadline", any(step.get("dueAt") for step in waiting["currentSteps"]))
            self.check(f"{label}: no child before timer ends", len(waiting.get("children", [])), 0)
            self.artifacts[f"progress/{label}/waiting.json"] = canonical(waiting)
            pending[label] = (run, next(step["dueAt"] for step in waiting["currentSteps"] if step["kind"] == "wait"))
        early = self.start("complex", "complex-early-rejection", {"items": ["Not started"]})
        self.decide(early, "one", "reject")
        rejected = self.finish(early, "complex-early-rejection", "Rejected")
        self.check("complex-early-rejection: no child work", len(rejected.get("children", [])), 0)
        self.check("complex-early-rejection: no open steps", rejected["currentSteps"], [])
        self.start("complex", "complex-over-bound", {"items": ["x"] * 6}, 422)
        for label, (run, due_at) in pending.items():
            parent = self.wait(run, lambda value: len(value.get("children", [])) == 2)
            self.artifacts[f"progress/{label}/children-ready.json"] = canonical(parent)
            children = {self.detail(child["id"])["input"]["title"]: child for child in parent["children"]}
            self.check(f"{label}: one child per input item", sorted(children), ["First item", "Second item"])
            for child in children.values():
                self.check(f"{label}: child pins migrated release {child['reference']}", child["release"]["id"], self.imported["small"]["release"]["id"])
                self.check(f"{label}: child starts after timer deadline {child['reference']}", datetime.fromisoformat(child["startedAt"].replace("Z", "+00:00")) >= datetime.fromisoformat(due_at.replace("Z", "+00:00")))
            if label == "complex-cancel":
                self.check("complex-cancel: parent cancelled", self.cancel(run)["lifecycle"], "cancelled")
                self.check("complex-cancel: every child cancelled", [self.detail(child["id"])["lifecycle"] for child in children.values()], ["cancelled", "cancelled"])
            else:
                self.decide(children["Second item"]["id"], "review", "reject" if label == "complex-mixed" else "approve")
                self.decide(children["First item"]["id"], "review")
                done = self.finish(run, label, "Rejected" if label == "complex-mixed" else "Accepted")
                self.check(f"{label}: collected outcomes follow input order", done["output"]["outcomeItems"], ["Accepted", "Rejected" if label == "complex-mixed" else "Accepted"])
                self.check(f"{label}: collected references follow input order", done["output"]["referenceItems"], [children[item]["reference"] for item in ("First item", "Second item")])

    def capture(self):
        runs = set(self.owned_runs)
        for run in self.owned_runs:
            runs.update(child["id"] for child in self.detail(run).get("children", []))
        for run in sorted(runs):
            self.artifacts[f"runs/{run}.json"] = canonical(self.detail(run))
            after, events = 0, []
            for _ in range(100):
                page = require(self.clients["operator"].get(self.path(f"runs/{run}/events"), after=after, limit=100), 200)
                events.extend(page["items"])
                if not page["hasMore"]:
                    break
                if page["nextAfter"] <= after:
                    raise RuntimeError("Timeline pagination did not advance")
                after = page["nextAfter"]
            else:
                raise RuntimeError("Timeline capture exceeded bounded page count")
            self.artifacts[f"events/{run}.json"] = canonical(events)

    def cleanup_failed(self):
        # Only requests created by this invocation are eligible for cleanup.
        for run in self.owned_runs:
            try:
                if self.detail(run)["lifecycle"] in {"running", "waiting", "needsAttention"}:
                    self.cancel(run)
            except (RuntimeError, KeyError):
                self.check("cleanup: active synthetic request requires operator attention", False)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", type=Path, default=DEFAULT_SUITE)
    parser.add_argument("--live", action="store_true", help="Import, publish and execute on an explicitly selected local stack")
    parser.add_argument("--base-url", default="http://localhost:18080")
    parser.add_argument("--out", type=Path, default=ROOT / "evidence/pega-synthetic-acceptance.zip")
    args = parser.parse_args(argv)
    suite, fixtures, artifacts = load_fixtures(args.suite)
    checks, scenario_ids, error_kind = [], [], None
    if args.live:
        base_url = local_url(args.base_url)
        os.environ["ORQIX_BASE_URL"] = base_url
        sys.path.insert(0, str(ROOT / "tools/api-e2e"))
        from orqix_client import Client
        acceptance = None
        try:
            clients = {role: Client(user) for role, user in {"designer": "dana.designer", "publisher": "paul.publisher", "requester": "rhea.requester",
                "manager": "mani.manager", "operator": "olga.operator", "outsider": "otto.outsider"}.items()}
            ws = next(workspace["id"] for workspace in require(clients["requester"].get("/me"), 200)["workspaces"] if workspace["key"] == "purchasing")
            acceptance = Acceptance(clients, ws, artifacts)
            acceptance.import_all(suite, fixtures)
            acceptance.execute()
            acceptance.capture()
        except Exception as error:
            error_kind = type(error).__name__
            print(f"Acceptance could not finish: {error_kind}", file=sys.stderr)
        finally:
            if acceptance:
                if error_kind or any(not check["passed"] for check in acceptance.checks):
                    acceptance.cleanup_failed()
                checks = acceptance.checks
                scenario_ids = acceptance.scenarios
    else:
        checks = [{"name": "synthetic archive/schema/provenance/dependency validation", "passed": True}]
    passed = not error_kind and bool(checks) and all(check["passed"] for check in checks)
    result = {"format": "orqix-pega-synthetic-acceptance-0.1", "passed": passed, "sourceValidation": "syntheticFixture",
              "nativePegaConversionVerified": False, "sourceRuntimeEquivalenceEstablished": False,
              "externalAcceptance": "pending genuine exports, independently observed source scenarios and owner sign-off",
              "executionMode": "liveLocalOrqixReviewedMapping" if args.live else "fixtureValidationOnly", "executionFailure": error_kind,
              "scenarioIds": scenario_ids, "checksPassed": sum(check["passed"] for check in checks), "checksTotal": len(checks), "checks": checks}
    checksum = write_pack(args.out, suite, {"executionMode": result["executionMode"], "checks": checks}, result, artifacts)
    print(json.dumps({key: result[key] for key in ("passed", "sourceValidation", "executionMode", "checksPassed", "checksTotal", "nativePegaConversionVerified", "sourceRuntimeEquivalenceEstablished")} | {"evidence": str(args.out), "sha256": checksum}))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
