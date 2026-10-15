"""Real Docker acceptance for reviewed OpenAPI imports and conditional row forms.

Uses the local dev realm through real OIDC. Set ORQIX_BASE_URL for an isolated
stack. Creates uniquely named workflows/connections; never changes bindings.
"""
import json
import sys
import uuid

import e2e_scenarios as base
from e2e_scenarios import check, wait_run
from orqix_client import Client


def require(response, status):
    if response.status_code != status:
        raise RuntimeError(f"Expected HTTP {status}, got {response.status_code}: {response.text[:500]}")
    return response.json()


def reviewed_import(admin, designer, outsider, ws):
    source = json.dumps({
        "openapi": "3.0.3", "info": {"title": "Acceptance API", "version": "1"},
        "servers": [{"url": "https://discard-user:discard-password@example.test/api?token=discard-token"}],
        "paths": {"/health": {"get": {"operationId": "health", "responses": {"200": {
            "description": "OK", "content": {"application/json": {"schema": {
                "type": "object", "required": ["status"], "properties": {"status": {"type": "string"}}
            }}}
        }}}}}
    })
    path = f"/workspaces/{ws}/connections/import-openapi"
    check("design permission alone cannot import connections", designer.post(path, {"source": source}).status_code == 403)
    check("foreign workspace import is concealed", outsider.post(path, {"source": source}).status_code == 404)
    review = require(admin.post(path, {"source": source}), 200)
    check("review produces no candidate before selection", review.get("definition") is None)
    check("report strips server credentials and query secrets", not any(
        value in json.dumps(review) for value in ("discard-user", "discard-password", "discard-token")))
    key = f"acceptance-api-{uuid.uuid4().hex[:8]}"
    selection = {"source": source, "key": key, "name": "Acceptance API", "version": 1,
                 "selections": [{"key": "health", "effectClass": "readOnly"}], "confirmEffects": False}
    check("selected effects require explicit confirmation", admin.post(path, selection).status_code == 422)
    selection["confirmEffects"] = True
    candidate = require(admin.post(path, selection), 200)["definition"]
    registered = admin.post(f"/workspaces/{ws}/connections/{key}/versions", {"definition": candidate})
    check("confirmed candidate registers separately", registered.status_code == 201, registered.text[:300])


def conditional_rows(designer, publisher, requester, ws):
    key = f"acceptance-rows-{uuid.uuid4().hex[:8]}"
    workflow = require(designer.post(f"/workspaces/{ws}/workflows", {
        "key": key, "name": "Conditional rows acceptance", "templateId": "purchase-approval"}), 201)
    path = f"/workspaces/{ws}/workflows/{workflow['id']}"
    draft = require(designer.get(path + "/draft"), 200)
    definition = draft["definition"]
    definition["inputSchema"]["properties"]["lines"] = {
        "type": "array", "title": "Line items", "minItems": 1, "maxItems": 3,
        "items": {"type": "object", "additionalProperties": False, "required": ["amount"],
                  "properties": {"amount": {"type": "number", "title": "Line amount", "minimum": 0,
                                             "maximum": 100, "multipleOf": 0.01}}}}
    definition["inputUi"]["visibleWhen"] = {
        "purpose": {"fieldRef": "/input/costCenter", "operator": "eq", "value": "OPS"}}
    definition["nodes"] = [
        {"id": "start", "type": "start", "label": "Submitted", "config": {}},
        {"id": "policy", "type": "decision", "label": "Check totals", "config": {"decisionRef": "line-policy"}},
        {"id": "end", "type": "end", "label": "Finished", "config": {"businessOutcome": "Completed", "outcomeKind": "success"}}]
    definition["transitions"] = [{"id": "begin", "from": "start", "to": "policy", "on": "next"},
                                  {"id": "finish", "from": "policy", "to": "end", "on": "next"}]
    definition["decisions"] = [{"key": "line-policy", "version": 1, "hitPolicy": "UNIQUE", "rows": [{
        "when": {"all": [{"fieldRef": "/input/lines/count", "operator": "gte", "value": 2},
                          {"fieldRef": "/input/lines/sum/amount", "operator": "gte", "value": 0.3}]},
        "then": {"approved": True}}], "default": {"approved": False}}]
    definition["outputSchema"] = {"type": "object", "additionalProperties": False,
        "required": ["count", "total", "approved"], "properties": {
            "count": {"type": "integer", "title": "Count"}, "total": {"type": "number", "title": "Total"},
            "approved": {"type": "boolean", "title": "Approved"}}}
    definition["outputMapping"] = {
        "count": {"fieldRef": "/input/lines/count", "required": True},
        "total": {"fieldRef": "/input/lines/sum/amount", "required": True},
        "approved": {"fieldRef": "/steps/policy/approved", "required": True}}
    definition["dependencies"] = {"connections": []}
    definition.pop("uiLayout", None)
    revision = require(designer.put(path + "/draft", {"definition": definition},
                                   if_match=f'"r{draft["revision"]}"'), 200)["revision"]
    require(publisher.post(path + "/publish", {"draftRevision": revision, "acknowledgeWarnings": True}, idem=True), 201)
    input_data = {"title": "Decimal row totals", "costCenter": "IT", "amount": 10,
                  "lines": [{"amount": 0.1}, {"amount": 0.2}]}
    start_path = f"/workspaces/{ws}/runs"
    forged = requester.post(start_path, {"workflowId": workflow["id"], "input": {**input_data, "purpose": "hidden"}}, idem=True)
    check("server rejects a forged hidden question", forged.status_code == 422 and "/input/purpose" in forged.text)
    invalid = requester.post(start_path, {"workflowId": workflow["id"], "input": {**input_data, "lines": [{"amount": "0.1", "extra": True}]}}, idem=True)
    check("row schema rejects string numbers and unknown cells", invalid.status_code == 422
          and "/input/lines/0/amount" in invalid.text and "/input/lines/0/extra" in invalid.text)
    simulation = require(designer.post(path + "/simulate", {"revision": revision, "input": input_data}), 200)
    started = require(requester.post(start_path, {"workflowId": workflow["id"], "input": input_data}, idem=True), 201)
    run = wait_run(requester, ws, started["id"], lambda r: r["lifecycle"] in ("completed", "needsAttention"), 30)
    check("row decision completes on the real engine", run["lifecycle"] == "completed")
    check("pinned DMN uses exact decimal sums and row counts", run.get("output") == {"count": 2, "total": 0.3, "approved": True}, str(run.get("output")))
    check("simulation agrees with real engine output", simulation.get("outputs") == run.get("output"))
    check("hidden question is absent in persisted request", "purpose" not in run.get("input", {}))
    input_data.pop("lines")
    empty_start = require(requester.post(start_path, {"workflowId": workflow["id"], "input": input_data}, idem=True), 201)
    empty_run = wait_run(requester, ws, empty_start["id"], lambda r: r["lifecycle"] in ("completed", "needsAttention"), 30)
    check("omitted optional rows produce zero aggregates", empty_run["lifecycle"] == "completed"
          and empty_run.get("output") == {"count": 0, "total": 0, "approved": False}, str(empty_run.get("output")))


def main():
    admin, designer, publisher, requester, outsider = (Client(user) for user in (
        "wade.admin", "dana.designer", "paul.publisher", "rhea.requester", "otto.outsider"))
    ws = next(w["id"] for w in requester.get("/me").json()["workspaces"] if w["key"] == "purchasing")
    reviewed_import(admin, designer, outsider, ws)
    conditional_rows(designer, publisher, requester, ws)
    failed = sum(not passed for _, passed in base.results)
    print(f"\nAcceptance: {len(base.results) - failed} passed, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
