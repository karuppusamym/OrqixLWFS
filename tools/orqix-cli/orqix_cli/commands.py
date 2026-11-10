"""orqix: sign in, pick a workspace, move workflows between files and Orqix, test them in CI (docs/build/S148).

Exit codes: 0 done, 1 a check failed (tests, coverage, contract tests, validation blockers), 2 wrong usage or input,
3 the server or the identity provider refused or could not be reached.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import sys
import uuid
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, TextIO

from orqix_cli import __version__, auth, config, packaging
from orqix_cli.api import ApiError, Client, quote

OK, FAILED, USAGE, REFUSED = 0, 1, 2, 3
TEST_FORMAT = "orqix.workflow-tests/v1"


class UsageError(Exception):
    pass


class Context:
    """What a command needs: settings, an API client, and where to write."""

    def __init__(self, args: argparse.Namespace, out: TextIO, err: TextIO,
                 client_factory: Optional[Callable[["Context"], Client]] = None):
        self.args = args
        self.out = out
        self.err = err
        self.settings = config.load()
        self._client_factory = client_factory
        self._client: Optional[Client] = None

    @property
    def server(self) -> str:
        return (getattr(self.args, "server", None) or self.settings.get("server") or config.DEFAULT_SERVER).rstrip("/")

    def client(self) -> Client:
        if self._client is None:
            if self._client_factory is not None:
                self._client = self._client_factory(self)
            else:
                self._client = Client(self.server, lambda: auth.access_token(self.settings, config.save))
        return self._client

    def workspace(self) -> str:
        chosen = getattr(self.args, "workspace", None) or self.settings.get("workspace")
        if not chosen:
            raise UsageError("No workspace chosen. Run orqix workspace use <key>, or pass --workspace.")
        if _looks_like_uuid(chosen):
            return chosen
        for w in self.client().get("/me").get("workspaces", []):
            if chosen in (w.get("key"), w.get("name")):
                return w["id"]
        raise UsageError(f"You are not a member of a workspace named {chosen}.")

    def print(self, text: str = "") -> None:
        print(text, file=self.out)

    def emit(self, data: Any) -> bool:
        """Prints the raw JSON when --json was given; returns True when it did."""
        if getattr(self.args, "json", False):
            print(json.dumps(data, indent=2, sort_keys=True), file=self.out)
            return True
        return False


def _looks_like_uuid(text: str) -> bool:
    parts = text.split("-")
    return len(parts) == 5 and all(all(c in "0123456789abcdefABCDEF" for c in p) for p in parts) and len(text) == 36


def _read_json(path: str) -> Any:
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except OSError as e:
        raise UsageError(f"{path} cannot be read.") from e
    except ValueError as e:
        raise UsageError(f"{path} is not valid JSON: {e}") from e


def _write(path: str, text: str) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(text, encoding="utf-8", newline="\n")


def _canonical(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def find_workflow(ctx: Context, key: str) -> Dict[str, Any]:
    ws = ctx.workspace()
    for item in ctx.client().pages(f"/workspaces/{ws}/workflows", {"q": key, "limit": 100}):
        if item.get("key") == key:
            return item
    raise UsageError(f"No workflow with the key {key} in this workspace.")


# ------------------------------------------------------------------------------------------------ sign-in, workspace

def cmd_login(ctx: Context) -> int:
    a = ctx.args
    settings = ctx.settings
    if a.server:
        settings["server"] = a.server.rstrip("/")
    if a.token_file:
        auth.read_token_file(a.token_file)
        settings["tokenFile"] = str(Path(a.token_file).resolve())
        settings.pop("token", None)
        config.save(settings)
        ctx.print(f"The token in {a.token_file} will be used (it is read each time, never copied).")
        return OK
    issuer = a.issuer or settings.get("issuer") or config.DEFAULT_ISSUER
    client_id = a.client_id or settings.get("clientId") or config.DEFAULT_CLIENT_ID
    record = auth.device_login(issuer, client_id, ctx.print)
    settings.update({"issuer": issuer, "clientId": client_id, "token": record})
    settings.pop("tokenFile", None)
    config.save(settings)
    me = ctx.client().get("/me")
    ctx.print(f"Signed in as {me['subject']['displayName']} on {ctx.server}.")
    return OK


def cmd_logout(ctx: Context) -> int:
    ctx.settings.pop("token", None)
    ctx.settings.pop("tokenFile", None)
    config.save(ctx.settings)
    ctx.print("Signed out. (The identity provider session, if any, stays until it ends.)")
    return OK


def cmd_whoami(ctx: Context) -> int:
    me = ctx.client().get("/me")
    if not ctx.emit(me):
        ctx.print(f"{me['subject']['displayName']} on {ctx.server}")
    return OK


def cmd_workspace_list(ctx: Context) -> int:
    me = ctx.client().get("/me")
    if ctx.emit(me.get("workspaces", [])):
        return OK
    chosen = ctx.settings.get("workspace")
    for w in me.get("workspaces", []):
        mark = "*" if chosen in (w["id"], w.get("key")) else " "
        ctx.print(f"{mark} {w.get('key', ''):<24} {w['name']}  ({w['id']})")
    return OK


def cmd_workspace_use(ctx: Context) -> int:
    for w in ctx.client().get("/me").get("workspaces", []):
        if ctx.args.workspace_ref in (w["id"], w.get("key"), w.get("name")):
            ctx.settings["workspace"] = w["id"]
            config.save(ctx.settings)
            ctx.print(f"Using {w['name']} ({w['id']}).")
            return OK
    raise UsageError(f"You are not a member of a workspace named {ctx.args.workspace_ref}.")


# ------------------------------------------------------------------------------------------------ workflows

def cmd_workflow_list(ctx: Context) -> int:
    ws = ctx.workspace()
    items = list(ctx.client().pages(f"/workspaces/{ws}/workflows", {"limit": 100}))
    if ctx.emit(items):
        return OK
    for w in items:
        release = w.get("activeRelease")
        state = f"release {release['releaseNumber']}" if release else "not published"
        ctx.print(f"{w['key']:<32} draft {w['draftRevision']:<4} {state:<14} {w['name']}")
    return OK


def cmd_workflow_export(ctx: Context) -> int:
    ws = ctx.workspace()
    wf = find_workflow(ctx, ctx.args.key)
    if ctx.args.release:
        if not wf.get("activeRelease"):
            raise UsageError(f"{ctx.args.key} has no active release.")
        release = ctx.client().get(f"/workspaces/{ws}/workflows/{wf['id']}/releases/{wf['activeRelease']['id']}")
        definition = release["definition"]
    else:
        definition = ctx.client().get(f"/workspaces/{ws}/workflows/{wf['id']}/draft")["definition"]
    text = json.dumps(definition, indent=2, ensure_ascii=False) + "\n"
    if ctx.args.output:
        _write(ctx.args.output, text)
        ctx.print(f"Wrote {ctx.args.key} to {ctx.args.output}.")
    else:
        ctx.out.write(text)
    return OK


def import_definition(ctx: Context, path: str) -> Dict[str, Any]:
    """Creates the workflow when its key is new and saves the file as a new draft revision when it differs.

    Returns {workflow, revision, saved}."""
    definition = _read_json(path)
    if not isinstance(definition, dict) or not definition.get("workflowKey"):
        raise UsageError(f"{path} is not a workflow definition (it needs workflowKey).")
    key = definition["workflowKey"]
    ws = ctx.workspace()
    client = ctx.client()
    try:
        wf = find_workflow(ctx, key)
    except UsageError:
        wf = client.post(f"/workspaces/{ws}/workflows", {"key": key, "name": definition.get("name") or key})
        ctx.print(f"Created the workflow {key}.")
    draft, headers = client.call("GET", f"/workspaces/{ws}/workflows/{wf['id']}/draft")
    if _canonical(draft["definition"]) == _canonical(definition):
        return {"workflow": wf, "revision": draft["revision"], "saved": False}
    saved, _ = client.call("PUT", f"/workspaces/{ws}/workflows/{wf['id']}/draft", body={"definition": definition},
                           headers={"If-Match": headers.get("etag", '"r%d"' % draft["revision"])})
    return {"workflow": wf, "revision": saved["revision"], "saved": True}


def cmd_workflow_import(ctx: Context) -> int:
    result = import_definition(ctx, ctx.args.file)
    key = result["workflow"]["key"]
    if result["saved"]:
        ctx.print(f"Saved {key} as draft revision {result['revision']}.")
    else:
        ctx.print(f"{key} draft revision {result['revision']} already matches {ctx.args.file}; nothing saved.")
    return OK


def _validate(ctx: Context, wf: Dict[str, Any], revision: int) -> Dict[str, Any]:
    ws = ctx.workspace()
    return ctx.client().post(f"/workspaces/{ws}/workflows/{wf['id']}/validate", {"revision": revision})


def _print_issues(ctx: Context, report: Dict[str, Any]) -> None:
    for issue in report.get("issues", []):
        where = issue.get("nodeId") or issue.get("path") or ""
        ctx.print(f"  {issue['severity']:<8} {issue['code']:<32} {where}  {issue['message']}")


def cmd_workflow_validate(ctx: Context) -> int:
    if ctx.args.file:
        result = import_definition(ctx, ctx.args.file)
        wf, revision = result["workflow"], result["revision"]
    else:
        if not ctx.args.key:
            raise UsageError("Give a definition file or --key.")
        wf = find_workflow(ctx, ctx.args.key)
        revision = wf["draftRevision"]
    report = _validate(ctx, wf, revision)
    if ctx.emit(report):
        return OK if report.get("publishable") else FAILED
    blockers = sum(1 for i in report.get("issues", []) if i.get("severity") == "blocker")
    warnings = len(report.get("issues", [])) - blockers
    ctx.print(f"{wf['key']} draft revision {revision}: {blockers} blocker(s), {warnings} warning(s).")
    _print_issues(ctx, report)
    return OK if report.get("publishable") else FAILED


def cmd_workflow_publish(ctx: Context) -> int:
    a = ctx.args
    result = import_definition(ctx, a.file)
    wf, revision = result["workflow"], result["revision"]
    report = _validate(ctx, wf, revision)
    if not report.get("publishable"):
        ctx.print(f"{wf['key']} draft revision {revision} cannot be published:")
        _print_issues(ctx, report)
        return FAILED
    if a.run_tests:
        run = run_tests(ctx, wf, None, revision=revision, release=False)
        if not run["passed"]:
            print_test_run(ctx, run)
            return FAILED
    ws = ctx.workspace()
    body: Dict[str, Any] = {"draftRevision": revision, "acknowledgeWarnings": bool(a.acknowledge_warnings),
                            "activate": not a.no_activate}
    if a.note:
        body["releaseNote"] = a.note
    # Same workflow and revision, same key: a retried publish replays instead of publishing twice.
    key = hashlib.sha256(f"orqix-cli:{wf['id']}:{revision}".encode()).hexdigest()[:40]
    release = ctx.client().post(f"/workspaces/{ws}/workflows/{wf['id']}/publish", body, headers={"Idempotency-Key": key})
    if not ctx.emit(release):
        ctx.print(f"Published {wf['key']} draft revision {revision} as release {release['releaseNumber']}"
                  + (" (active)." if release.get("isActive") else "."))
    return OK


# ------------------------------------------------------------------------------------------------ runs and tasks

def _input(args: argparse.Namespace) -> Dict[str, Any]:
    values: Dict[str, Any] = {}
    if args.input:
        loaded = _read_json(args.input)
        if not isinstance(loaded, dict):
            raise UsageError(f"{args.input} must hold one JSON object (the request form answers).")
        values.update(loaded)
    for pair in args.set or []:
        if "=" not in pair:
            raise UsageError(f"--set {pair}: use name=value.")
        name, raw = pair.split("=", 1)
        try:
            values[name] = json.loads(raw)
        except ValueError:
            values[name] = raw
    return values


def cmd_run_start(ctx: Context) -> int:
    ws = ctx.workspace()
    wf = find_workflow(ctx, ctx.args.key)
    body: Dict[str, Any] = {"workflowId": wf["id"], "input": _input(ctx.args)}
    if ctx.args.business_key:
        body["businessKey"] = ctx.args.business_key
    # Pass --idempotency-key to make a retried start (for example a re-run CI job) start the request only once.
    headers = {"Idempotency-Key": ctx.args.idempotency_key or str(uuid.uuid4())}
    run = ctx.client().post(f"/workspaces/{ws}/runs", body, headers=headers)
    if not ctx.emit(run):
        ctx.print(f"Started {run.get('reference', run['id'])} ({run['lifecycle']}).")
    return OK


def cmd_run_list(ctx: Context) -> int:
    ws = ctx.workspace()
    query: Dict[str, Any] = {"limit": min(ctx.args.limit, 100)}
    if ctx.args.workflow:
        query["workflowId"] = find_workflow(ctx, ctx.args.workflow)["id"]
    if ctx.args.lifecycle:
        query["lifecycle"] = ctx.args.lifecycle
    runs = list(ctx.client().pages(f"/workspaces/{ws}/runs", query, limit=ctx.args.limit))
    if ctx.emit(runs):
        return OK
    for r in runs:
        ctx.print(f"{r.get('reference', r['id']):<12} {r['lifecycle']:<16} {r['workflow']['key']:<28} {r['createdAt']}")
    return OK


def cmd_task_list(ctx: Context) -> int:
    ws = ctx.workspace()
    query: Dict[str, Any] = {"limit": min(ctx.args.limit, 100)}
    if ctx.args.view:
        query["view"] = ctx.args.view
    tasks = list(ctx.client().pages(f"/workspaces/{ws}/tasks", query, limit=ctx.args.limit))
    if ctx.emit(tasks):
        return OK
    for t in tasks:
        assignee = (t.get("assignee") or {}).get("displayName") or "unassigned"
        ctx.print(f"{t.get('runReference', ''):<12} {t['label']:<32} {t['status']:<10} {assignee:<20} {t['workflowName']}")
    return OK


# ------------------------------------------------------------------------------------------------ tests

def run_tests(ctx: Context, wf: Dict[str, Any], suite: Optional[Dict[str, Any]], revision: Optional[int],
              release: bool) -> Dict[str, Any]:
    ws = ctx.workspace()
    body: Dict[str, Any] = {}
    if suite is not None:
        body["suite"] = suite
    if release:
        body["useActiveRelease"] = True
    elif revision is not None:
        body["revision"] = revision
    return ctx.client().post(f"/workspaces/{ws}/workflows/{wf['id']}/test-runs", body)


def print_test_run(ctx: Context, run: Dict[str, Any]) -> None:
    source = run.get("source") or {}
    where = (f"draft revision {source['revision']}" if source.get("revision") is not None
             else f"release {source.get('releaseNumber')}")
    ctx.print(f"{run['workflowKey']} ({where}, {run['suite']} suite): {run['passedCount']} of {run['total']} passed"
              + (f", {run['invalidCount']} could not run" if run.get("invalidCount") else "")
              + (" (recorded for publication)" if run.get("recorded") else ""))
    for r in run.get("results", []):
        mark = "PASS" if r["passed"] else ("ERROR" if r["status"] == "invalid" else "FAIL")
        ctx.print(f"  {mark:<5} {r['name']} [{r['id']}]")
        for f in r.get("failures", []):
            ctx.print(f"        {f.get('label')}: expected {f.get('expected')}, got {f.get('actual')}")
        if r.get("problem"):
            ctx.print(f"        {r['problem']}")
    coverage = run.get("coverage")
    if coverage:
        ctx.print("  " + coverage.get("summary", ""))
    for s in run.get("coverageShortfalls", []):
        ctx.print(f"  Coverage of {s['kind']} is {s['percent']}%, below the minimum of {s['minimum']}%.")
    ctx.print("PASSED" if run["passed"] else "FAILED")


def load_suite(path: str) -> Dict[str, Any]:
    suite = _read_json(path)
    if not isinstance(suite, dict) or suite.get("schemaVersion") != TEST_FORMAT:
        raise UsageError(f"{path} is not a {TEST_FORMAT} test file.")
    return suite


def cmd_test_run(ctx: Context) -> int:
    a = ctx.args
    if not a.shared and not a.files:
        raise UsageError("Give one or more test files, or --shared with --workflow.")
    runs: List[Dict[str, Any]] = []
    if a.shared:
        if not a.workflow:
            raise UsageError("--shared needs --workflow.")
        runs.append(run_tests(ctx, find_workflow(ctx, a.workflow), None, a.revision, a.release))
    for path in a.files or []:
        suite = load_suite(path)
        key = a.workflow or suite.get("workflowKey")
        if not key:
            raise UsageError(f"{path} names no workflowKey; pass --workflow.")
        runs.append(run_tests(ctx, find_workflow(ctx, key), suite, a.revision, a.release))
    if a.junit:
        _write(a.junit, merge_junit([r["junitXml"] for r in runs]))
    if not ctx.emit(runs if len(runs) > 1 else runs[0]):
        for run in runs:
            print_test_run(ctx, run)
        if a.junit:
            ctx.print(f"JUnit XML written to {a.junit}.")
    return OK if all(r["passed"] for r in runs) else FAILED


def merge_junit(reports: List[str]) -> str:
    """One JUnit file with every testsuite of the given reports (each holds one testsuites element)."""
    if len(reports) == 1:
        return reports[0]
    import xml.etree.ElementTree as ET
    merged = ET.Element("testsuites", {"name": "orqix"})
    totals = {"tests": 0, "failures": 0, "errors": 0}
    for text in reports:
        root = ET.fromstring(text.encode("utf-8"))
        for suite in root.findall("testsuite"):
            merged.append(suite)
            for k in totals:
                totals[k] += int(suite.get(k, "0"))
    for k, v in totals.items():
        merged.set(k, str(v))
    merged.set("time", "0")
    ET.indent(merged)
    return '<?xml version="1.0" encoding="UTF-8"?>\n' + ET.tostring(merged, encoding="unicode") + "\n"


def cmd_test_pull(ctx: Context) -> int:
    ws = ctx.workspace()
    wf = find_workflow(ctx, ctx.args.key)
    suite = ctx.client().get(f"/workspaces/{ws}/workflows/{wf['id']}/test-suite")
    document: Dict[str, Any] = {"schemaVersion": TEST_FORMAT, "workflowKey": wf["key"]}
    if suite.get("coverageMinimum"):
        document["coverageMinimum"] = suite["coverageMinimum"]
    document["cases"] = suite.get("cases", [])
    text = json.dumps(document, indent=2, ensure_ascii=False) + "\n"
    if ctx.args.output:
        _write(ctx.args.output, text)
        ctx.print(f"Wrote {len(document['cases'])} shared test(s) of {wf['key']} (suite version {suite['version']}) to"
                  f" {ctx.args.output}.")
    else:
        ctx.out.write(text)
    return OK


def cmd_test_push(ctx: Context) -> int:
    suite = load_suite(ctx.args.file)
    key = ctx.args.workflow or suite.get("workflowKey")
    if not key:
        raise UsageError("The test file names no workflowKey; pass --workflow.")
    ws = ctx.workspace()
    wf = find_workflow(ctx, key)
    current = ctx.client().get(f"/workspaces/{ws}/workflows/{wf['id']}/test-suite")
    # The shared suite keeps the server's case shape; the Test tab's editing state stays in the file.
    cases = [{k: v for k, v in c.items() if k != "studio"} for c in suite.get("cases", [])]
    body: Dict[str, Any] = {"expectedVersion": current["version"], "cases": cases}
    if suite.get("coverageMinimum"):
        body["coverageMinimum"] = suite["coverageMinimum"]
    saved, _ = ctx.client().call("PUT", f"/workspaces/{ws}/workflows/{wf['id']}/test-suite", body=body)
    ctx.print(f"Shared {len(cases)} test(s) as suite version {saved['version']} of {wf['key']}."
              " Run orqix test run --shared to record a run for publication.")
    return OK


# ------------------------------------------------------------------------------------------------ packages

def cmd_package_test(ctx: Context) -> int:
    a = ctx.args
    path = Path(a.package)
    if not path.exists():
        raise UsageError(f"{a.package} does not exist.")
    try:
        archive = packaging.load(path)
    except (ValueError, OSError) as e:
        raise UsageError(str(e)) from e
    ws = ctx.workspace()
    report = ctx.client().post(f"/workspaces/{ws}/connector-packages/contract-tests",
                               {"archive": base64.b64encode(archive).decode("ascii")})
    passed = bool(report["passed"])
    smoke: List[Dict[str, Any]] = []
    if a.smoke:
        smoke = smoke_run(ctx, archive, a)
        passed = passed and all(s["passed"] for s in smoke)
    junit = report["junitXml"]
    if smoke:
        junit = merge_junit([junit, smoke_junit(report.get("packageId") or "package", smoke)])
    if a.junit:
        _write(a.junit, junit)
    if ctx.emit({"contract": report, "smoke": smoke}):
        return OK if passed else FAILED
    ctx.print(f"{report.get('packageId')}@{report.get('version')} ({report['signatureStatus']}):"
              f" {report['fixturesPassed']} of {report['fixtures']} fixtures passed.")
    for p in report.get("problems", []):
        ctx.print(f"  PROBLEM {p['file']}{p['pointer']} {p['code']}: {p['message']}")
    for f in report.get("results", []):
        mark = "PASS" if f["passed"] else "FAIL"
        ctx.print(f"  {mark:<5} {f['path']}" + ("" if f["passed"] else f"  {f.get('message')}"))
    for s in smoke:
        mark = "PASS" if s["passed"] else "FAIL"
        ctx.print(f"  {mark:<5} smoke {s['connection']}.{s['operation']}" + ("" if s["passed"] else f"  {s['detail']}"))
    if a.junit:
        ctx.print(f"JUnit XML written to {a.junit}.")
    ctx.print("PASSED" if passed else "FAILED")
    return OK if passed else FAILED


def smoke_run(ctx: Context, archive: bytes, a: argparse.Namespace) -> List[Dict[str, Any]]:
    """Calls each read-only operation of the package through this workspace's binding (testConnection).

    Only read-only operations are ever called; the server also refuses any other kind."""
    ws = ctx.workspace()
    wanted = set(a.operation or [])
    inputs = _read_json(a.smoke_input) if a.smoke_input else {}
    results = []
    versions: Dict[str, int] = {}
    for op in packaging.read_only_operations(archive):
        if wanted and op["operation"] not in wanted:
            continue
        key = op["connection"]
        if key not in versions:
            detail = ctx.client().get(f"/workspaces/{ws}/connections/{quote(key)}")
            versions[key] = max(d["version"] for d in detail.get("definitions", [])) if detail.get("definitions") else 0
        body: Dict[str, Any] = {"version": versions[key], "operation": op["operation"]}
        if isinstance(inputs.get(op["operation"]), dict):
            body["input"] = inputs[op["operation"]]
        try:
            answer = ctx.client().post(f"/workspaces/{ws}/connections/{quote(key)}/test", body)
            ok = answer.get("outcome") == "succeeded"
            detail_text = "" if ok else f"{answer.get('errorCode') or ''} {answer.get('detail') or ''}".strip()
            results.append({**op, "passed": ok, "httpStatus": answer.get("httpStatus"), "detail": detail_text})
        except ApiError as e:
            results.append({**op, "passed": False, "httpStatus": None, "detail": f"{e.code}: {e.detail}"})
    return results


def smoke_junit(package_id: str, results: List[Dict[str, Any]]) -> str:
    from xml.sax.saxutils import quoteattr
    failures = sum(1 for r in results if not r["passed"])
    lines = ['<?xml version="1.0" encoding="UTF-8"?>',
             f'<testsuites name="orqix" tests="{len(results)}" failures="{failures}" errors="0" time="0">',
             f'  <testsuite name={quoteattr(package_id + " smoke")} tests="{len(results)}" failures="{failures}"'
             ' errors="0" skipped="0" time="0">']
    for r in results:
        name = quoteattr(f"{r['connection']}.{r['operation']}")
        cls = quoteattr("orqix.package." + package_id + ".smoke")
        if r["passed"]:
            lines.append(f'    <testcase classname={cls} name={name} time="0"/>')
        else:
            lines.append(f'    <testcase classname={cls} name={name} time="0">')
            lines.append(f'      <failure message={quoteattr(r["detail"] or "The call failed.")} type="SmokeFailure"/>')
            lines.append("    </testcase>")
    lines += ["  </testsuite>", "</testsuites>", ""]
    return "\n".join(lines)


# ------------------------------------------------------------------------------------------------ parser

def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="orqix", description="The Orqix command line tool.")
    p.add_argument("--version", action="version", version="orqix " + __version__)
    p.add_argument("--server", help="Orqix address (default: the one you signed in to, or " + config.DEFAULT_SERVER + ")")
    p.add_argument("--workspace", help="Workspace key, name or ID (default: orqix workspace use)")
    p.add_argument("--json", action="store_true", help="Print the API answer as JSON")
    sub = p.add_subparsers(dest="command", required=True)

    login = sub.add_parser("login", help="Sign in with a device code, or use a token file")
    login.add_argument("--issuer", help="Identity provider issuer (default " + config.DEFAULT_ISSUER + ")")
    login.add_argument("--client-id", help="OAuth client (default " + config.DEFAULT_CLIENT_ID + ")")
    login.add_argument("--token-file", help="Use the access token in this file instead (CI)")
    login.set_defaults(func=cmd_login)
    sub.add_parser("logout", help="Forget the stored sign-in").set_defaults(func=cmd_logout)
    sub.add_parser("whoami", help="Who you are signed in as").set_defaults(func=cmd_whoami)

    ws = sub.add_parser("workspace", help="List or choose workspaces").add_subparsers(dest="action", required=True)
    ws.add_parser("list").set_defaults(func=cmd_workspace_list)
    use = ws.add_parser("use")
    use.add_argument("workspace_ref", metavar="workspace")
    use.set_defaults(func=cmd_workspace_use)

    wf = sub.add_parser("workflow", help="Export, import, validate and publish workflows").add_subparsers(
        dest="action", required=True)
    wf.add_parser("list").set_defaults(func=cmd_workflow_list)
    exp = wf.add_parser("export", help="Write a workflow definition (draft, or --release) to a file")
    exp.add_argument("key")
    exp.add_argument("-o", "--output")
    exp.add_argument("--release", action="store_true", help="The active release instead of the draft")
    exp.set_defaults(func=cmd_workflow_export)
    imp = wf.add_parser("import", help="Create or update a workflow draft from a file")
    imp.add_argument("file")
    imp.set_defaults(func=cmd_workflow_import)
    val = wf.add_parser("validate", help="Run every compiler check (a file is saved as a draft first, when it differs)")
    val.add_argument("file", nargs="?")
    val.add_argument("--key")
    val.set_defaults(func=cmd_workflow_validate)
    pub = wf.add_parser("publish", help="Import a file, validate it and publish it")
    pub.add_argument("file")
    pub.add_argument("--note", help="Release note")
    pub.add_argument("--acknowledge-warnings", action="store_true")
    pub.add_argument("--no-activate", action="store_true", help="Publish without making it the release for new starts")
    pub.add_argument("--run-tests", action="store_true", help="Run and record the shared tests first (publication gate)")
    pub.set_defaults(func=cmd_workflow_publish)

    run = sub.add_parser("run", help="Start and list requests").add_subparsers(dest="action", required=True)
    start = run.add_parser("start")
    start.add_argument("key", help="Workflow key")
    start.add_argument("--input", help="JSON file with the request form answers")
    start.add_argument("--set", action="append", metavar="NAME=VALUE", help="One answer (JSON value or text)")
    start.add_argument("--business-key")
    start.add_argument("--idempotency-key")
    start.set_defaults(func=cmd_run_start)
    rl = run.add_parser("list")
    rl.add_argument("--workflow")
    rl.add_argument("--lifecycle")
    rl.add_argument("--limit", type=int, default=50)
    rl.set_defaults(func=cmd_run_list)

    task = sub.add_parser("task", help="List tasks").add_subparsers(dest="action", required=True)
    tl = task.add_parser("list")
    tl.add_argument("--view", help="Task list view, for example mine or available")
    tl.add_argument("--limit", type=int, default=50)
    tl.set_defaults(func=cmd_task_list)

    test = sub.add_parser("test", help="Run, pull and push workflow test suites").add_subparsers(dest="action", required=True)
    tr = test.add_parser("run", help="Run test files (or --shared); non-zero exit on failure")
    tr.add_argument("files", nargs="*")
    tr.add_argument("--workflow", help="Workflow key (default: the file's workflowKey)")
    tr.add_argument("--revision", type=int, help="Draft revision (default: the current draft)")
    tr.add_argument("--release", action="store_true", help="Test the active release")
    tr.add_argument("--shared", action="store_true", help="Run the workflow's shared suite (recorded for publication)")
    tr.add_argument("--junit", help="Write JUnit XML to this file")
    tr.set_defaults(func=cmd_test_run)
    pull = test.add_parser("pull", help="Write the shared suite as a test file")
    pull.add_argument("key")
    pull.add_argument("-o", "--output")
    pull.set_defaults(func=cmd_test_pull)
    push = test.add_parser("push", help="Replace the shared suite with a test file")
    push.add_argument("file")
    push.add_argument("--workflow")
    push.set_defaults(func=cmd_test_push)

    pkg = sub.add_parser("package", help="Connector package contract tests").add_subparsers(dest="action", required=True)
    pt = pkg.add_parser("test", help="Replay the fixtures of a package folder or .orqpkg; --smoke also calls read-only"
                                     " operations through this workspace's binding")
    pt.add_argument("package")
    pt.add_argument("--junit")
    pt.add_argument("--smoke", action="store_true")
    pt.add_argument("--operation", action="append", help="Smoke only these read-only operations")
    pt.add_argument("--smoke-input", help="JSON file {operation: input} for the smoke calls")
    pt.set_defaults(func=cmd_package_test)
    return p


def main(argv: Optional[List[str]] = None, out: TextIO = sys.stdout, err: TextIO = sys.stderr,
         client_factory: Optional[Callable[[Context], Client]] = None) -> int:
    try:
        args = parser().parse_args(argv)
    except SystemExit as e:
        return int(e.code or 0) if e.code in (0, None) else USAGE
    ctx = Context(args, out, err, client_factory)
    try:
        return args.func(ctx)
    except UsageError as e:
        print(f"orqix: {e}", file=err)
        return USAGE
    except auth.AuthError as e:
        print(f"orqix: {e}", file=err)
        return REFUSED
    except ApiError as e:
        print(f"orqix: {e.detail} ({e.code}, HTTP {e.status})", file=err)
        for fe in e.field_errors()[:20]:
            print(f"  {fe.get('pointer', '')}: {fe.get('message', '')}", file=err)
        return FAILED if e.code == "WORKFLOW_INVALID" else REFUSED


def cli() -> None:
    """Console entry point (pyproject [project.scripts])."""
    sys.exit(main())
