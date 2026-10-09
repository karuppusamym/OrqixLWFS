"""Design judge (S10 section 7.2): scores screenshots with a low-cost vision model against a rubric drawn from
docs/02-ux-design.md and docs/build/S7-frontend.md, and lists concrete issues for the design backlog. It never fails
a build. Capture the screens first with capture.mjs (frontend mock mode).

Usage (from the repository root):
  python tools/ai-eval/design_judge.py --screens evidence/ai-eval/<stamp>/screens
"""
from __future__ import annotations

import argparse
import base64
import json
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import OpenRouter, load_key  # noqa: E402

RUBRIC = """You are a senior product designer reviewing one screen of Orqix, a business workflow product for
requesters, approvers, designers and operators (not developers). Judge only what is visible in the screenshot.
Score each criterion 1 (poor) to 5 (excellent):
1. visualHierarchy: one clear page title, the primary content and the primary action stand out; secondary things recede.
2. spacingRhythm: consistent spacing on an 8 px rhythm, aligned edges, no cramped or orphaned elements.
3. readability: body text about 16 px, short business wording, no jargon (BPMN, JSON, IDs) for business users, enough contrast.
4. statusClarity: states (waiting, running, completed, rejected, needs attention, AI-generated, errors) use icon + text, not color alone.
5. consistency: buttons, cards, badges, tables and dialogs look like one design system; radius, borders and colors are consistent.
6. obviousNextAction: the person can tell what to do next on this screen (one primary action, clear empty states).
7. accessibilityRedFlags: 5 = none visible; lower for low contrast, tiny targets (< 24 px, primary < 44 px), missing
   labels, focus or text cut off, information carried by color alone.
Then list at most 8 of the most important concrete issues (where on the screen, what is wrong, a specific fix), most
severe first, each in one short sentence.
Do not invent issues you cannot see. Reply with JSON only:
{"scores": {"visualHierarchy": n, "spacingRhythm": n, "readability": n, "statusClarity": n, "consistency": n,
 "obviousNextAction": n, "accessibilityRedFlags": n}, "overall": n, "issues": [{"severity": "high|medium|low",
 "where": "...", "issue": "...", "fix": "..."}], "strengths": ["..."]}"""
SCHEMA = {"type": "object", "properties": {
    "scores": {"type": "object", "additionalProperties": {"type": "integer"}},
    "overall": {"type": "number"},
    "issues": {"type": "array", "items": {"type": "object", "properties": {
        "severity": {"type": "string"}, "where": {"type": "string"}, "issue": {"type": "string"}, "fix": {"type": "string"}},
        "required": ["severity", "where", "issue", "fix"]}},
    "strengths": {"type": "array", "items": {"type": "string"}}},
    "required": ["scores", "overall", "issues"]}
SEVERITY = {"high": 0, "medium": 1, "low": 2}


def review(client: OpenRouter, model: str, screens: Path, item: dict) -> dict:
    image = base64.b64encode((screens / item["file"]).read_bytes()).decode("ascii")
    content = [{"type": "text", "text": f"Screen: {item['screen']}. Signed in as: {item['persona']}. Viewport: {item['viewport']}."},
               {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{image}"}}]
    messages = [{"role": "system", "content": RUBRIC}, {"role": "user", "content": content}]
    result = {"screen": item["name"], "description": item["screen"], "cost": 0.0}
    # Structured outputs occasionally come back as an empty object; retry once in plain JSON mode.
    for schema in (SCHEMA, None):
        reply = client.chat(model, messages, max_tokens=4000, schema=schema, schema_name="design_review",
                            temperature=None, price_cap=False)
        result.update(cost=round(result["cost"] + float(reply.get("cost") or 0), 6), latency_s=reply.get("latency_s"),
                      model=reply.get("model"))
        if reply.get("error"):
            result["error"] = reply["error"]
            continue
        try:
            text = reply["content"]
            review_json = json.loads(text[text.index("{"): text.rindex("}") + 1])
        except (ValueError, KeyError):
            result["error"] = "unparseable review"
            result["finishReason"] = reply.get("finish_reason")
            result["rawTail"] = (reply.get("content") or "")[-300:]
            continue
        if review_json.get("scores") and review_json.get("overall"):
            result.pop("error", None)
            result.update(review_json)
            break
    print(f"  {item['name']}: overall={result.get('overall')} issues={len(result.get('issues', []))}", flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--screens", required=True, help="directory with manifest.json and PNGs from capture.mjs")
    parser.add_argument("--model", default="anthropic/claude-haiku-5.5", help="vision model (image input required)")
    parser.add_argument("--max-spend", type=float, default=0.5)
    args = parser.parse_args()
    screens = Path(args.screens)
    manifest = json.loads((screens / "manifest.json").read_text(encoding="utf-8"))
    client = OpenRouter(load_key(), args.max_spend)
    info = client.models().get(args.model)
    if not info or "image" not in info.get("architecture", {}).get("input_modalities", []):
        sys.exit(f"{args.model} does not accept image input according to /models; choose another --model")
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda item: review(client, args.model, screens, item), manifest))
    out = screens.parent
    (out / "design-judge.json").write_text(json.dumps({"model": args.model, "spentUsd": round(client.spent, 6),
                                                        "results": results}, indent=2, ensure_ascii=False), encoding="utf-8")
    lines = ["# Design judge", "", f"Model: {args.model}; screens: {len(results)}; spend USD {client.spent:.4f}. "
             "Advisory input for the design backlog; it never fails a build.", "",
             "| Screen | Overall | Hierarchy | Spacing | Readability | Status | Consistency | Next action | A11y flags |",
             "|---|---|---|---|---|---|---|---|---|"]
    for r in results:
        s = r.get("scores", {})
        lines.append(f"| {r['screen']} | {r.get('overall')} | {s.get('visualHierarchy')} | {s.get('spacingRhythm')} | "
                     f"{s.get('readability')} | {s.get('statusClarity')} | {s.get('consistency')} | "
                     f"{s.get('obviousNextAction')} | {s.get('accessibilityRedFlags')} |")
    issues = sorted(((SEVERITY.get(i.get("severity", "low"), 3), r["screen"], i) for r in results for i in r.get("issues", [])),
                    key=lambda x: (x[0], x[1]))
    lines += ["", "## Issues (most severe first)", ""]
    for _, screen, i in issues:
        lines.append(f"- **{i.get('severity')}** [{screen}] {i.get('where')}: {i.get('issue')} Fix: {i.get('fix')}")
    (out / "design-judge.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"spent USD {client.spent:.4f}; report: {out / 'design-judge.md'}")


if __name__ == "__main__":
    main()
