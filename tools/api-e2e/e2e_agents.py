"""Demo 2 end to end: agent -> MCP tool -> rule -> approval (scenarios 20 to 23).

  20  Vendor onboarding check on the real stack: an AI agent asks the vendor registry (MCP tool server in the procurement
      stub) about the vendor, the decision tables route on the structured result: high risk -> compliance review ->
      approved; low risk -> approved automatically; sanctions hit -> compliance review. The run timeline carries the
      business sentence; the stub's call log shows the tool call that was made (and none that was not allowed).
  21  Connection test of the agent with a sample input (admin only, validated input, metered)
  22  Tool server fault: the stub answers HTTP 500 twice on tools/call; the step is retried and the run completes
  23  Without a provider key: publishing warns (WF_AGENT_AI_UNAVAILABLE) and the test says AI is not configured

Needs the Docker stack (app :8080, Keycloak :8180, procurement stub :8095). Scenarios 20-22 call a real model through
OpenRouter (the app's OPENROUTER_API_KEY, economy profile, roughly USD 0.001 per run); when AI is not enabled they are
skipped and scenario 23 runs instead. The key itself is never read or printed here: only the app talks to the provider.

    python tools/api-e2e/e2e_agents.py [--max-cost 0.10] [--faults]
"""
import argparse
import sys
import time
import uuid

import requests

import e2e_scenarios as base
from e2e_scenarios import STUB, check, claim_and_decide, wait_run
from orqix_client import Client

SAMPLE = {"country": "United Kingdom", "annualSpend": 12000}
KEY = "vendor-onboarding-check"


def finish(code):
    passed = sum(1 for _, ok in base.results if ok)
    print(f"\nRESULT: {passed}/{len(base.results)} checks passed")
    return code if passed == len(base.results) else 1


def mcp_calls():
    return requests.get(f"{STUB}/__mcp/calls", timeout=10).json()


def start_vendor(rhea, ws, wf_id, vendor):
    return rhea.post(f"/workspaces/{ws}/runs", {"workflowId": wf_id, "input": {"vendorName": vendor, **SAMPLE}}, idem=True)


def agent_events(client, ws, run_id):
    events = client.get(f"/workspaces/{ws}/runs/{run_id}/events").json()["items"]
    return [e for e in events if e["type"] == "EFFECT_SUCCEEDED" and (e.get("data") or {}).get("summary")]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--max-cost", type=float, default=0.10, help="fail when the agent steps cost more (USD)")
    parser.add_argument("--faults", action="store_true", help="also run scenario 22 (retry after tool server faults, ~2 min)")
    args = parser.parse_args()

    dana, paul, rhea, fin, wade, olga = (Client(u) for u in (
        "dana.designer", "paul.publisher", "rhea.requester", "fin.finance", "wade.admin", "olga.operator"))
    ws = next(w["id"] for w in rhea.get("/me").json()["workspaces"] if w["key"] == "purchasing")
    status = dana.get(f"/workspaces/{ws}/ai/status").json()
    wf = base.find_workflow(paul, ws, KEY)
    if wf is None:
        print(f"SKIP scenarios 20-23: the dev seed has no workflow '{KEY}'. Rebuild and restart the stack (dev profile).")
        return 0
    requests.post(f"{STUB}/__reset", timeout=10)

    draft = paul.get(f"/workspaces/{ws}/workflows/{wf['id']}/draft").json()
    revision = draft["revision"]

    if not status.get("enabled"):
        print(f"SKIP scenarios 20-22: AI is not enabled here ({status.get('reason')}). Set OPENROUTER_API_KEY for the app "
              "and restart it to run Demo 2 against the real provider.")
        print("Scenario 23: AI not configured, publishing warns and the test refuses")
        report = dana.post(f"/workspaces/{ws}/workflows/{wf['id']}/validate", {"revision": revision}).json()
        codes = {i["code"] for i in report["issues"]}
        check("validation warns that AI is unavailable (not a blocker)", "WF_AGENT_AI_UNAVAILABLE" in codes
              and report["publishable"], str(sorted(codes)))
        refused = paul.post(f"/workspaces/{ws}/workflows/{wf['id']}/publish", {"draftRevision": revision}, idem=True)
        check("publish without acknowledging the warning -> 422 WARNINGS_NOT_ACKNOWLEDGED",
              refused.status_code == 422 and refused.json().get("code") == "WARNINGS_NOT_ACKNOWLEDGED", refused.text[:160])
        test = wade.post(f"/workspaces/{ws}/connections/vendor-risk-assistant/test",
                         {"version": 1, "operation": "assessVendor", "input": {"vendorName": "Acme Ltd", **SAMPLE}})
        check("connection test -> 422 AI_NOT_CONFIGURED", test.status_code == 422
              and test.json().get("code") == "AI_NOT_CONFIGURED", f"{test.status_code} {test.text[:160]}")
        check("the tool server was never contacted", mcp_calls()["count"] == 0)
        return finish(0)

    print("Scenario 20: Demo 2, agent -> MCP tool -> rule -> approval")
    if not wf.get("activeRelease"):
        published = paul.post(f"/workspaces/{ws}/workflows/{wf['id']}/publish",
                              {"draftRevision": revision, "acknowledgeWarnings": True}, idem=True)
        check("paul.publisher publishes the vendor workflow", published.status_code == 201, published.text[:200])
    cost = 0.0

    started = time.time()
    run = start_vendor(rhea, ws, wf["id"], "Acme Ltd")
    check("start (Acme Ltd) -> 201", run.status_code == 201, f"{run.status_code} {run.text[:200]}")
    run_id = run.json()["id"]
    run = wait_run(rhea, ws, run_id, lambda x: x["lifecycle"] in ("waiting", "needsAttention", "completed"), timeout=120)
    check("high risk waits at Compliance review", run["lifecycle"] == "waiting" and run["currentSteps"]
          and run["currentSteps"][0]["nodeId"] == "compliance",
          f"{run['lifecycle']} {run.get('attention') or run.get('currentSteps')}")
    sentences = [e["data"]["summary"] for e in agent_events(rhea, ws, run_id)]
    print(f"    agent step took {time.time() - started:.0f}s: {sentences}")
    check("the timeline carries the business sentence",
          sentences == ["Vendor risk assistant checked 'Acme Ltd' using Vendor registry: risk 72 (high)"], str(sentences))
    meta = agent_events(rhea, ws, run_id)[0]["data"].get("agent", {}) if sentences else {}
    cost += float(meta.get("costUsd", 0) or 0)
    check("the event records model and tool names, no payloads", bool(meta.get("model"))
          and [t["name"] for t in meta.get("toolCalls", [])] == ["vendor_risk_lookup"]
          and "Acme" not in str(meta), str(meta))
    tool_calls = [c for c in mcp_calls()["calls"] if c["method"] == "tools/call"]
    check("the tool server saw exactly one vendor_risk_lookup for Acme Ltd", len(tool_calls) == 1
          and tool_calls[0]["tool"] == "vendor_risk_lookup" and tool_calls[0]["arguments"] == {"vendorName": "Acme Ltd"},
          str(tool_calls))
    check("requester sees no compliance task", base.task_for(rhea, ws, run_id) is None)
    _, decided = claim_and_decide(fin, ws, run_id, "approve")
    check("compliance (fin.finance) approves -> 201", decided is not None and decided.status_code == 201,
          str(decided.status_code if decided is not None else "no task"))
    run = wait_run(rhea, ws, run_id, lambda x: x["lifecycle"] in ("completed", "needsAttention"))
    check("run completed as Approved with the registry result in its output", run["lifecycle"] == "completed"
          and run.get("outcome") == "Approved" and (run.get("output") or {}).get("riskScore") == 72,
          f"{run['lifecycle']} {run.get('outcome')} {run.get('output')}")

    run = start_vendor(rhea, ws, wf["id"], "Globex Corporation").json()
    run = wait_run(rhea, ws, run["id"], lambda x: x["lifecycle"] in ("completed", "needsAttention", "waiting"), timeout=120)
    check("low risk is approved automatically (no compliance task)", run["lifecycle"] == "completed"
          and run.get("outcome") == "Approved" and (run.get("output") or {}).get("riskScore") == 18,
          f"{run['lifecycle']} {run.get('outcome')} {run.get('attention')}")
    cost += float((agent_events(rhea, ws, run["id"])[0]["data"].get("agent", {}) or {}).get("costUsd", 0) or 0)

    run = start_vendor(rhea, ws, wf["id"], "Umbrella Trading").json()
    run = wait_run(rhea, ws, run["id"], lambda x: x["lifecycle"] in ("completed", "needsAttention", "waiting"), timeout=120)
    check("a sanctions hit goes to compliance review", run["lifecycle"] == "waiting" and run["currentSteps"]
          and run["currentSteps"][0]["nodeId"] == "compliance", f"{run['lifecycle']} {run.get('attention')}")
    _, rejected = claim_and_decide(fin, ws, run["id"], "reject", reason="Sanctioned vendor")
    run = wait_run(rhea, ws, run["id"], lambda x: x["lifecycle"] in ("completed", "needsAttention"))
    check("compliance rejects -> completed as Rejected", run["lifecycle"] == "completed" and run.get("outcome") == "Rejected",
          f"{run['lifecycle']} {run.get('outcome')}")
    check(f"agent cost of scenario 20 within USD {args.max_cost}", cost <= args.max_cost, f"{cost}")
    print(f"    agent cost so far: USD {cost}")

    print("Scenario 21: connection test of the agent")
    sample = {"vendorName": "Initech", **SAMPLE}
    result = wade.post(f"/workspaces/{ws}/connections/vendor-risk-assistant/test",
                       {"version": 1, "operation": "assessVendor", "input": sample})
    check("admin test -> 200 succeeded with a structured output", result.status_code == 200
          and result.json().get("outcome") == "succeeded" and result.json()["output"]["riskScore"] == 45
          and result.json()["summary"].startswith("Vendor risk assistant checked 'Initech'"),
          f"{result.status_code} {result.text[:240]}")
    invalid = wade.post(f"/workspaces/{ws}/connections/vendor-risk-assistant/test",
                        {"version": 1, "operation": "assessVendor", "input": {"vendorName": "x"}})
    check("input that does not match the operation -> 422 (no AI spent)", invalid.status_code == 422, str(invalid.status_code))
    check("requester cannot test -> 403", rhea.post(f"/workspaces/{ws}/connections/vendor-risk-assistant/test",
                                                    {"version": 1, "operation": "assessVendor", "input": sample}).status_code == 403)
    summary = next(c for c in wade.get(f"/workspaces/{ws}/connections").json()["items"] if c["key"] == "vendor-risk-assistant")
    check("the connection list marks the agent (kind agent, effect class readOnly)", summary.get("kind") == "agent"
          and all(o["effectClass"] == "readOnly" for o in summary["operations"]), str(summary))

    if args.faults:
        print("Scenario 22: tool server fails twice (HTTP 500), the step is retried, the run completes")
        requests.post(f"{STUB}/__reset", timeout=10)
        requests.post(f"{STUB}/__mcp/faults", json={"mode": "status500", "times": 2}, timeout=10)
        run = start_vendor(rhea, ws, wf["id"], "Globex Corporation").json()
        run = wait_run(rhea, ws, run["id"], lambda x: x["lifecycle"] in ("completed", "needsAttention"), timeout=240)
        effects = olga.get(f"/workspaces/{ws}/runs/{run['id']}").json().get("effects", [])
        check("completed after retries", run["lifecycle"] == "completed" and run.get("outcome") == "Approved",
              f"{run['lifecycle']} {run.get('attention')} attempts={[e['attempts'] for e in effects]}")
        check("the effect needed more than one attempt", any(e["attempts"] >= 2 for e in effects), str(effects))
        requests.post(f"{STUB}/__mcp/faults", json={"mode": "none"}, timeout=10)
    return finish(0)


if __name__ == "__main__":
    sys.exit(main())
