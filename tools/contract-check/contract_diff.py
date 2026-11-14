#!/usr/bin/env python3
"""Orqix API compatibility check (CG-K05, docs/api-compatibility.md).

Compares contracts/openapi.yaml with the recorded baseline contracts/compat/openapi-v1-baseline.json and fails on a
breaking change to /api/v1:

  * an operation was removed (method + path), unless the baseline marks it deprecated and at least two releases were
    recorded after the release that deprecated it;
  * a required response field was removed or became optional;
  * a response enum was narrowed (a value removed, or an enum placed on a field that had none);
  * a request gained a required field, a required parameter, or a required body.

Additive changes (new operations, new optional request fields, new response fields, new enum values in requests or
responses) pass and are listed in the generated changelog section of docs/api-changelog.md.

Usage::

    python tools/contract-check/contract_diff.py                  # check; exit 1 on a breaking change
    python tools/contract-check/contract_diff.py --changelog      # rewrite the generated changelog section
    python tools/contract-check/contract_diff.py --record RELEASE # record a new baseline at a release

The baseline is not the OpenAPI file itself but its *surface*: per operation, the parameters and the flattened request
and response fields with their required flags and enums. It is deterministic (sorted keys) so a diff of it reads like a
contract review.

Dependencies: pyyaml (tools/contract-check/requirements.txt). No network, no clock.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

import yaml

HTTP_METHODS = ("get", "put", "post", "delete", "patch")
BASELINE = Path("contracts/compat/openapi-v1-baseline.json")
OPENAPI = Path("contracts/openapi.yaml")
CHANGELOG = Path("docs/api-changelog.md")
BEGIN = "<!-- BEGIN GENERATED: unreleased changes (python tools/contract-check/contract_diff.py --changelog) -->"
END = "<!-- END GENERATED -->"
# Releases a deprecated operation must stay for after the release that deprecated it.
DEPRECATION_WINDOW = 2
MAX_DEPTH = 8
SURFACE_FORMAT = 1


# ---------------------------------------------------------------------------------------------------------- surface


class _Flattener:
    """Flattens a JSON Schema into {field path: {"required": bool, "types": [...], "enum": [...]?}}.

    Paths use ``.`` for properties, ``[]`` for array items and ``{}`` for additionalProperties values. ``required`` is
    local: the field is required whenever its parent object is present. Alternatives (oneOf/anyOf) contribute the
    union of their fields; a field is required only when every object alternative requires it."""

    def __init__(self, document: dict[str, Any]):
        self.document = document

    def resolve(self, schema: Any, stack: tuple[str, ...]) -> tuple[Any, tuple[str, ...]] | tuple[None, None]:
        while isinstance(schema, dict) and "$ref" in schema:
            ref = schema["$ref"]
            if not ref.startswith("#/"):
                return {"type": "external", "x-ref": ref}, stack
            if ref in stack:
                return None, None  # recursive schema: stop here
            node: Any = self.document
            for token in ref[2:].split("/"):
                node = node[token.replace("~1", "/").replace("~0", "~")]
            stack = stack + (ref,)
            # Siblings of $ref (description, nullable) do not change the shape.
            schema = node
        return schema, stack

    def shape(self, schema: Any, stack: tuple[str, ...] = (), depth: int = 0) -> dict[str, Any]:
        """Returns {"types": set, "enum": set|None, "nullable": bool, "props": {name: (required, shape)},
        "items": shape|None, "values": shape|None}."""
        out: dict[str, Any] = {"types": set(), "enum": None, "props": {}, "items": None, "values": None}
        schema, stack = self.resolve(schema, stack)
        if schema is None:
            out["types"].add("recursive")
            return out
        if not isinstance(schema, dict) or depth > MAX_DEPTH:
            out["types"].add("any")
            return out
        declared = schema.get("type")
        types = declared if isinstance(declared, list) else [declared] if declared else []
        out["types"].update(t for t in types if t)
        if "enum" in schema:
            out["enum"] = {json.dumps(v, sort_keys=True) for v in schema["enum"]}
        if "const" in schema:
            out["enum"] = {json.dumps(schema["const"], sort_keys=True)}
        required = set(schema.get("required", []))
        for name, sub in (schema.get("properties") or {}).items():
            # PyYAML reads a bare `on`/`yes` property name as a boolean; property names are always text.
            name = name if isinstance(name, str) else str(name).lower()
            out["props"][name] = (name in required, self.shape(sub, stack, depth + 1))
        if "items" in schema and isinstance(schema["items"], dict):
            out["items"] = self.shape(schema["items"], stack, depth + 1)
        extra = schema.get("additionalProperties")
        if isinstance(extra, dict):
            out["values"] = self.shape(extra, stack, depth + 1)
        for sub in schema.get("allOf", []):
            self._merge(out, self.shape(sub, stack, depth + 1), all_of=True)
        alternatives = [self.shape(sub, stack, depth + 1) for sub in schema.get("oneOf", []) + schema.get("anyOf", [])]
        if alternatives:
            self._merge_alternatives(out, alternatives)
        if not out["types"] and (out["props"] or out["values"]):
            out["types"].add("object")
        return out

    @staticmethod
    def _merge(into: dict[str, Any], other: dict[str, Any], all_of: bool) -> None:
        into["types"] |= other["types"]
        if other["enum"] is not None:
            into["enum"] = other["enum"] if into["enum"] is None or not all_of else into["enum"] & other["enum"]
        for name, (req, sub) in other["props"].items():
            if name in into["props"]:
                old_req, old_sub = into["props"][name]
                into["props"][name] = (old_req or req, old_sub)
            else:
                into["props"][name] = (req, sub)
        into["items"] = into["items"] or other["items"]
        into["values"] = into["values"] or other["values"]

    def _merge_alternatives(self, into: dict[str, Any], alternatives: list[dict[str, Any]]) -> None:
        objects = [a for a in alternatives if a["props"]]
        enums = [a["enum"] for a in alternatives if a["enum"] is not None]
        plain = [a for a in alternatives if a["enum"] is None and a["types"] - {"null"} and not a["props"]]
        for alt in alternatives:
            into["types"] |= alt["types"]
            into["items"] = into["items"] or alt["items"]
            into["values"] = into["values"] or alt["values"]
        if enums and not plain:
            merged = set().union(*enums)
            into["enum"] = merged if into["enum"] is None else into["enum"] | merged
        names = set().union(*(a["props"].keys() for a in objects)) if objects else set()
        for name in names:
            present = [a["props"][name] for a in objects if name in a["props"]]
            required = len(present) == len(objects) and all(r for r, _ in present)
            if name in into["props"]:
                required = required or into["props"][name][0]
            into["props"][name] = (required, present[0][1])


def _fields(shape: dict[str, Any], prefix: str = "", out: dict[str, Any] | None = None) -> dict[str, Any]:
    out = {} if out is None else out
    for name, (required, sub) in sorted(shape["props"].items()):  # names are text (see shape)
        path = f"{prefix}.{name}" if prefix else name
        out[path] = _field_entry(required, sub)
        _fields(sub, path, out)
    if shape["items"] is not None:
        path = f"{prefix}[]"
        out[path] = _field_entry(True, shape["items"])
        _fields(shape["items"], path, out)
    if shape["values"] is not None:
        path = f"{prefix}{{}}"
        out[path] = _field_entry(False, shape["values"])
        _fields(shape["values"], path, out)
    return out


def _field_entry(required: bool, shape: dict[str, Any]) -> str:
    """One line per field keeps the baseline small and its diffs readable: ``R string``, ``O null|string``,
    ``R string enum=["a","b"]`` (R required, O optional)."""
    text = ("R " if required else "O ") + ("|".join(sorted(shape["types"])) or "any")
    if shape["enum"] is not None:
        text += " enum=[" + ",".join(sorted(shape["enum"])) + "]"
    return text


def parse_entry(text: str) -> dict[str, Any]:
    head, _, enum = text.partition(" enum=")
    flag, _, types = head.partition(" ")
    entry: dict[str, Any] = {"required": flag == "R", "types": types.split("|")}
    if enum:
        entry["enum"] = [json.dumps(v, sort_keys=True) for v in json.loads(enum)]
    return entry


def _body_surface(flattener: _Flattener, content: dict[str, Any] | None) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for media, spec in sorted((content or {}).items()):
        schema = (spec or {}).get("schema")
        shape = flattener.shape(schema) if schema is not None else None
        if shape is None:
            result[media] = {"root": "R any", "fields": {}}
            continue
        result[media] = {"root": _field_entry(True, shape), "fields": _fields(shape)}
    return result


def _parameter(document: dict[str, Any], param: dict[str, Any]) -> dict[str, Any]:
    if "$ref" in param:
        node: Any = document
        for token in param["$ref"][2:].split("/"):
            node = node[token]
        return node
    return param


def build_surface(document: dict[str, Any]) -> dict[str, Any]:
    """Returns {"operations": {"METHOD path": ...}, "sharedResponses": {name: body surface}}. Responses that reference
    components/responses (the problem answers) are recorded once under sharedResponses and by name per operation."""
    flattener = _Flattener(document)
    operations: dict[str, Any] = {}
    shared_responses: dict[str, Any] = {}
    for name, response in sorted(((document.get("components") or {}).get("responses") or {}).items()):
        shared_responses[name] = _body_surface(flattener, (response or {}).get("content"))
    for path, item in sorted((document.get("paths") or {}).items()):
        shared = [_parameter(document, p) for p in item.get("parameters", [])]
        for method in HTTP_METHODS:
            op = item.get(method)
            if not isinstance(op, dict):
                continue
            params: dict[str, str] = {}
            for p in shared + [_parameter(document, p) for p in op.get("parameters", [])]:
                shape = flattener.shape(p.get("schema", {}))
                params[f"{p['in']}:{p['name']}"] = _field_entry(bool(p.get("required", False)), shape)
            body = op.get("requestBody")
            if body and "$ref" in body:
                body = _parameter(document, body)
            responses: dict[str, Any] = {}
            for status, response in sorted((op.get("responses") or {}).items(), key=lambda kv: str(kv[0])):
                ref = response.get("$ref", "") if isinstance(response, dict) else ""
                if ref.startswith("#/components/responses/"):
                    responses[str(status)] = "ref:" + ref.rsplit("/", 1)[1]
                else:
                    responses[str(status)] = _body_surface(flattener, (response or {}).get("content"))
            entry_op: dict[str, Any] = {
                "operationId": op.get("operationId"),
                "parameters": params,
                "requestBody": None if not body else {
                    "required": bool(body.get("required", False)),
                    "content": _body_surface(flattener, body.get("content")),
                },
                "responses": responses,
            }
            if op.get("deprecated"):
                entry_op["deprecated"] = True
                entry_op["deprecatedIn"] = op.get("x-orqix-deprecated-in")
            operations[f"{method.upper()} {path}"] = entry_op
    return {"operations": operations, "sharedResponses": shared_responses}


def load_openapi(root: Path) -> dict[str, Any]:
    with (root / OPENAPI).open(encoding="utf-8") as handle:
        return yaml.safe_load(handle)


def load_baseline(root: Path) -> dict[str, Any]:
    with (root / BASELINE).open(encoding="utf-8") as handle:
        return json.load(handle)


def deprecation_problems(document: dict[str, Any]) -> list[str]:
    """A deprecated operation names the release that deprecated it (``x-orqix-deprecated-in``) and declares the
    ``Deprecation`` and ``Sunset`` headers (RFC 9745, RFC 8594) on each success response."""
    problems: list[str] = []
    for path, item in sorted((document.get("paths") or {}).items()):
        for method in HTTP_METHODS:
            op = item.get(method)
            if not isinstance(op, dict) or not op.get("deprecated"):
                continue
            label = f"{method.upper()} {path} ({op.get('operationId')})"
            if not op.get("x-orqix-deprecated-in"):
                problems.append(f"{label} is deprecated without x-orqix-deprecated-in")
            for status, response in (op.get("responses") or {}).items():
                if not str(status).startswith("2"):
                    continue
                if isinstance(response, dict) and "$ref" in response:
                    response = _parameter(document, response)
                headers = {name.lower() for name in ((response or {}).get("headers") or {})}
                for header in ("deprecation", "sunset"):
                    if header not in headers:
                        problems.append(f"{label} response {status} does not declare the {header.title()} header")
    return problems


# ---------------------------------------------------------------------------------------------------------- compare


class Diff:
    def __init__(self) -> None:
        self.breaking: list[str] = []
        self.additive: list[str] = []
        self.deprecated: list[str] = []
        self.removed_after_window: list[str] = []


def _parent(path: str) -> str | None:
    """Parent field path, or "" for top-level fields."""
    match = re.match(r"^(.*?)(\.[^.\[\]{}]+|\[\]|\{\})$", path)
    if match:
        return match.group(1)
    return ""


def _releases_after(releases: list[str], release: str | None) -> int:
    if release is None or release not in releases:
        return 0
    return len(releases) - 1 - releases.index(release)


def compare(baseline: dict[str, Any], current: dict[str, Any], current_release: str | None = None) -> Diff:
    """``baseline`` is the recorded file; ``current`` is build_surface() of the contract being checked."""
    diff = Diff()
    base_ops: dict[str, Any] = baseline["operations"]
    current_ops: dict[str, Any] = current["operations"]
    base_shared: dict[str, Any] = baseline.get("sharedResponses", {})
    current_shared: dict[str, Any] = current.get("sharedResponses", {})
    releases: list[str] = list(baseline.get("releases", []))
    if current_release and current_release not in releases:
        releases.append(current_release)

    for key in sorted(base_ops.keys() - current_ops.keys()):
        old = base_ops[key]
        if old.get("deprecated") and _releases_after(releases, old.get("deprecatedIn")) >= DEPRECATION_WINDOW:
            diff.removed_after_window.append(f"`{key}` ({old.get('operationId')}) removed after its deprecation window")
        else:
            why = "it is not deprecated" if not old.get("deprecated") else (
                f"its deprecation window ({DEPRECATION_WINDOW} releases after {old.get('deprecatedIn')}) has not passed")
            diff.breaking.append(f"`{key}` ({old.get('operationId')}) was removed, but {why}")
    for key in sorted(current_ops.keys() - base_ops.keys()):
        diff.additive.append(f"New operation `{key}` ({current_ops[key].get('operationId')})")

    for name in sorted(base_shared.keys() & current_shared.keys()):
        _compare_bodies(diff, f"shared response `{name}`", base_shared[name], current_shared[name])

    for key in sorted(base_ops.keys() & current_ops.keys()):
        old, new = base_ops[key], current_ops[key]
        label = f"`{key}`"
        if new.get("deprecated") and not old.get("deprecated"):
            diff.deprecated.append(f"{label} ({new.get('operationId')}) deprecated in {new.get('deprecatedIn')}")
        _compare_parameters(diff, label, old["parameters"], new["parameters"])
        _compare_request(diff, label, old.get("requestBody"), new.get("requestBody"))
        for status in sorted(old["responses"].keys() & new["responses"].keys()):
            old_body, new_body = old["responses"][status], new["responses"][status]
            if isinstance(old_body, str) and old_body == new_body:
                continue  # the same shared response, compared once above
            if isinstance(old_body, str):
                old_body = base_shared.get(old_body[4:], {})
            if isinstance(new_body, str):
                new_body = current_shared.get(new_body[4:], {})
            _compare_bodies(diff, f"{label} {status}", old_body, new_body)
    return diff


def _compare_bodies(diff: Diff, label: str, old: dict[str, Any], new: dict[str, Any]) -> None:
    for media in sorted(old.keys() & new.keys()):
        _compare_response(diff, f"{label} {media}", old[media], new[media])


def _compare_parameters(diff: Diff, label: str, old: dict[str, str], new: dict[str, str]) -> None:
    for name in sorted(new):
        current = parse_entry(new[name])
        before = parse_entry(old[name]) if name in old else None
        if current["required"] and not (before and before["required"]):
            if name.startswith("path:") and before is None:
                continue  # a path parameter only appears with a new path, which is a new operation
            diff.breaking.append(f"{label} parameter `{name}` is now required")
        elif before is None:
            diff.additive.append(f"{label} new optional parameter `{name}`")
        elif "enum" in before and "enum" in current and set(before["enum"]) - set(current["enum"]):
            removed = ", ".join(sorted(set(before["enum"]) - set(current["enum"])))
            diff.breaking.append(f"{label} parameter `{name}` no longer accepts {removed}")


def _compare_request(diff: Diff, label: str, old: dict[str, Any] | None, new: dict[str, Any] | None) -> None:
    if new is None:
        return
    if old is None:
        if new["required"]:
            diff.breaking.append(f"{label} now requires a request body")
        return
    if new["required"] and not old["required"]:
        diff.breaking.append(f"{label} request body is now required")
    for media in sorted(old["content"].keys() & new["content"].keys()):
        old_fields = old["content"][media]["fields"]
        new_fields = new["content"][media]["fields"]
        for path in sorted(new_fields):
            field = parse_entry(new_fields[path])
            parent = _parent(path)
            parent_existed = parent == "" or parent in old_fields
            was_required = path in old_fields and parse_entry(old_fields[path])["required"]
            if field["required"] and not was_required and parent_existed and not path.endswith("[]"):
                diff.breaking.append(f"{label} request field `{path}` ({media}) is now required")
            elif path not in old_fields and parent_existed and not path.endswith(("[]", "{}")):
                diff.additive.append(f"{label} new optional request field `{path}`")


def _compare_response(diff: Diff, label: str, old: dict[str, Any], new: dict[str, Any]) -> None:
    old_fields, new_fields = old["fields"], new["fields"]
    _compare_enum(diff, label, "(body)", parse_entry(old["root"]), parse_entry(new["root"]))
    reported: list[str] = []
    for path in sorted(old_fields):
        if any(path.startswith(r) and path[len(r):][:1] in (".", "[", "{") for r in reported):
            continue  # the parent was reported already
        field = parse_entry(old_fields[path])
        parent = _parent(path)
        if parent and parent not in new_fields:
            continue
        if path not in new_fields:
            if field["required"]:
                diff.breaking.append(f"{label} required response field `{path}` was removed")
                reported.append(path)
            continue
        current = parse_entry(new_fields[path])
        if field["required"] and not current["required"]:
            diff.breaking.append(f"{label} response field `{path}` is no longer required")
            reported.append(path)
        _compare_enum(diff, label, f"`{path}`", field, current)
    for path in sorted(new_fields.keys() - old_fields.keys()):
        parent = _parent(path)
        if (parent == "" or parent in old_fields) and not path.endswith(("[]", "{}")):
            diff.additive.append(f"{label} new response field `{path}`")


def _compare_enum(diff: Diff, label: str, what: str, old: dict[str, Any], new: dict[str, Any]) -> None:
    if "enum" in new and "enum" not in old:
        diff.breaking.append(f"{label} response {what} is now limited to {', '.join(new['enum'])}")
    elif "enum" in old and "enum" in new:
        removed = set(old["enum"]) - set(new["enum"])
        added = set(new["enum"]) - set(old["enum"])
        if removed:
            diff.breaking.append(f"{label} response {what} no longer returns {', '.join(sorted(removed))}")
        if added:
            diff.additive.append(f"{label} response {what} may also return {', '.join(sorted(added))}")


# ---------------------------------------------------------------------------------------------------------- outputs


def changelog_section(baseline: dict[str, Any], diff: Diff) -> str:
    release = (baseline.get("releases") or ["(none)"])[-1]
    lines = [BEGIN, "", f"### Unreleased (compared with the baseline recorded at {release})", ""]

    def block(title: str, items: list[str]) -> None:
        lines.append(f"#### {title}")
        lines.append("")
        lines.extend(f"- {item}" for item in items) if items else lines.append("- None.")
        lines.append("")

    block("Breaking changes (CI fails while this lists any)", diff.breaking)
    block("Deprecated", diff.deprecated)
    block("Removed after the deprecation window", diff.removed_after_window)
    block("Added", diff.additive)
    lines.append(END)
    return "\n".join(lines)


def write_changelog(root: Path, section: str) -> bool:
    """Replaces the generated section; returns True when the file changed."""
    path = root / CHANGELOG
    text = path.read_text(encoding="utf-8")
    start, end = text.find(BEGIN), text.find(END)
    if start < 0 or end < 0:
        raise SystemExit(f"{CHANGELOG.as_posix()} lacks the generated-section markers")
    updated = text[:start] + section + text[end + len(END):]
    if updated != text:
        path.write_text(updated, encoding="utf-8", newline="\n")
        return True
    return False


def changelog_is_current(root: Path, section: str) -> bool:
    text = (root / CHANGELOG).read_text(encoding="utf-8")
    start, end = text.find(BEGIN), text.find(END)
    return start >= 0 and end >= 0 and text[start:end + len(END)] == section


def record(root: Path, release: str) -> None:
    document = load_openapi(root)
    try:
        releases = list(load_baseline(root).get("releases", []))
    except FileNotFoundError:
        releases = []
    if release in releases:
        raise SystemExit(f"release {release} is already recorded")
    releases.append(release)
    data = {"format": SURFACE_FORMAT, "api": "/api/v1", "releases": releases, **build_surface(document)}
    path = root / BASELINE
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=1, sort_keys=True) + "\n", encoding="utf-8", newline="\n")


def run_check(root: Path) -> tuple[Diff, dict[str, Any]]:
    baseline = load_baseline(root)
    if baseline.get("format") != SURFACE_FORMAT:
        raise SystemExit(f"unsupported baseline format {baseline.get('format')}")
    return compare(baseline, build_surface(load_openapi(root))), baseline


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--changelog", action="store_true", help="rewrite the generated section of docs/api-changelog.md")
    group.add_argument("--record", metavar="RELEASE", help="record the current contract as the baseline of RELEASE")
    args = parser.parse_args(argv)
    root = args.root.resolve()

    if args.record:
        record(root, args.record)
        _, baseline = run_check(root)
        write_changelog(root, changelog_section(baseline, Diff()))
        print(f"Recorded {BASELINE.as_posix()} at release {args.record}; the changelog section is reset.")
        return 0

    diff, baseline = run_check(root)
    problems = deprecation_problems(load_openapi(root))
    for problem in problems:
        print(f"  DEPRECATION  {problem}")
    section = changelog_section(baseline, diff)
    if args.changelog:
        changed = write_changelog(root, section)
        print(f"{CHANGELOG.as_posix()}: {'updated' if changed else 'already current'}")
    for item in diff.breaking:
        print(f"  BREAKING  {item}")
    print(f"{len(diff.breaking)} breaking, {len(diff.additive)} additive, {len(diff.deprecated)} deprecated, "
          f"{len(diff.removed_after_window)} removed after the window (baseline {baseline['releases'][-1]})")
    if diff.breaking or problems:
        print("Breaking changes are not allowed in /api/v1, and deprecations need their release and headers "
              "(docs/api-compatibility.md).")
        return 1
    if not args.changelog and not changelog_is_current(root, section):
        print(f"{CHANGELOG.as_posix()} is out of date: run python tools/contract-check/contract_diff.py --changelog")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
