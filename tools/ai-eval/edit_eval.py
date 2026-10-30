"""Conversational edit eval (S124): one turn per case in edit-cases.json through the product's draft-edit prompt.

Uses the backend's own PromptLibrary (draft-edit-v2) and DraftEdits rules over the AiEvalCli bridge, so a change is
counted only when the product would offer it (shape checks and the real compiler). Spends money; never part of CI.

    python tools/ai-eval/edit_eval.py --max-spend 0.5
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import REPO, TOOL, Bridge, OpenRouter, load_key, stamp  # noqa: E402

MODELS = {"economy": "deepseek/deepseek-v3.2"}


def grade(case: dict, result: dict) -> list[str]:
    expect, problems = case["expect"], []
    changes, questions = result.get("changes", []), result.get("questions", [])
    ops = {op for change in changes for op in change["ops"]}
    if len(questions) < expect.get("questionsMin", 0):
        problems.append(f"expected at least {expect['questionsMin']} question(s), got {len(questions)}")
    if "questionsMax" in expect and len(questions) > expect["questionsMax"]:
        problems.append(f"expected at most {expect['questionsMax']} question(s), got {len(questions)}")
    if len(changes) < expect.get("changesMin", 0):
        problems.append(f"expected at least {expect['changesMin']} offered change(s), got {len(changes)}"
                        + (f"; rejected: {result.get('rejected')}" if result.get("rejected") else ""))
    if "changesMax" in expect and len(changes) > expect["changesMax"]:
        problems.append(f"expected at most {expect['changesMax']} change(s), got {len(changes)}")
    for op in expect.get("ops", []):
        if op not in ops:
            problems.append(f"no offered change uses {op}")
    if expect.get("notAnEdit") and not result.get("notAnEdit"):
        problems.append("expected notAnEdit")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--profile", default="economy", choices=sorted(MODELS))
    parser.add_argument("--cases", default="", help="only these case ids")
    parser.add_argument("--max-spend", type=float, default=0.5)
    parser.add_argument("--backend", default=str(REPO / "backend"))
    parser.add_argument("--no-build", action="store_true")
    parser.add_argument("--out", default=str(REPO / "evidence" / "ai-eval" / stamp()))
    args = parser.parse_args()

    cases = json.loads((TOOL / "edit-cases.json").read_text(encoding="utf-8"))["cases"]
    if args.cases:
        cases = [c for c in cases if c["id"] in set(args.cases.split(","))]
    definition = json.loads((REPO / "examples" / "purchase-approval.json").read_text(encoding="utf-8"))
    workspace = str(TOOL / "workspace.json")
    client = OpenRouter(load_key(), args.max_spend)
    bridge = Bridge(Path(args.backend), build=not args.no_build)
    rows = []
    for case in cases:
        prompt = bridge.request(op="editMessages", workspace=workspace, definition=definition,
                                instruction=case["instruction"], history=[], mode="edit", questions=case["questions"])
        reply = client.chat(MODELS[args.profile], [{"role": "system", "content": prompt["system"]},
                                                    {"role": "user", "content": prompt["user"]}],
                            max_tokens=6000, schema=prompt["responseSchema"], schema_name="orqix_draft_edits")
        result = {} if reply.get("error") else bridge.request(op="editEvaluate", workspace=workspace,
                                                              definition=definition, content=reply["content"], mode="edit")
        problems = [f"provider error: {reply['error']}"] if reply.get("error") else grade(case, result)
        rows.append({"id": case["id"], "passed": not problems, "problems": problems, "result": result,
                     "cost": reply.get("cost"), "promptVersion": prompt["promptVersion"]})
        print(f"{'PASS' if not problems else 'FAIL'} {case['id']}" + ("" if not problems else f": {'; '.join(problems)}"))
    bridge.close()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    passed = sum(r["passed"] for r in rows)
    (out / "edit-results.json").write_text(json.dumps({"passed": passed, "total": len(rows), "rows": rows,
                                                       "spentUsd": round(client.spent, 6)}, indent=2, ensure_ascii=False),
                                           encoding="utf-8")
    print(f"{passed}/{len(rows)} passed, USD {client.spent:.4f} -> {out}")


if __name__ == "__main__":
    main()
