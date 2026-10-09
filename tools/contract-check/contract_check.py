#!/usr/bin/env python3
"""Orqix contract check (CI stage 1).

Checks, in order:
  1. Both JSON Schemas are valid JSON Schema 2020-12 documents and all their local $refs resolve.
  2. Every examples/*.json workflow validates against contracts/workflow-v0.schema.json and every
     examples/connections/*.json validates against contracts/connection-definition-v0.schema.json.
  3. examples/fixtures/compiler-cases.json: every RFC 6902 patch applies to the base document; cases that
     expect WF_SCHEMA_INVALID / WF_SCHEMA_VERSION_UNSUPPORTED fail schema validation, all others pass it.
     (Semantic-layer codes are the compiler's job and are not evaluated here.)
  4. contracts/openapi.yaml structure: every $ref resolves (including external files), operationIds are
     unique, path parameters match the path templates, every operation has x-orqix-permission and every
     POST/PUT/DELETE has x-orqix-audit (except validateDraft, evaluateDecision, markNotificationRead).

Exit status 0 when every check passes, 1 otherwise. Dependencies: pyyaml, jsonschema (requirements.txt).
"""
from __future__ import annotations

import argparse
import copy
import json
import re
import sys
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urldefrag

import yaml
from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError

SCHEMA_FAILURE_CODES = {"WF_SCHEMA_INVALID", "WF_SCHEMA_VERSION_UNSUPPORTED"}
HTTP_METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")
AUDIT_REQUIRED_METHODS = ("post", "put", "delete")
AUDIT_EXEMPT_OPERATIONS = {"validateDraft", "evaluateDecision", "markNotificationRead"}


class Report:
    def __init__(self) -> None:
        self.checks = 0
        self.failures: list[str] = []

    def section(self, title: str) -> None:
        print(f"\n== {title}")

    def ok(self, message: str) -> None:
        self.checks += 1
        print(f"  PASS  {message}")

    def fail(self, message: str) -> None:
        self.checks += 1
        self.failures.append(message)
        print(f"  FAIL  {message}")

    def check(self, condition: bool, ok_message: str, fail_message: str) -> bool:
        if condition:
            self.ok(ok_message)
        else:
            self.fail(fail_message)
        return condition


# --------------------------------------------------------------------------------------------------
# JSON helpers
# --------------------------------------------------------------------------------------------------

def load_document(path: Path) -> Any:
    text = path.read_text(encoding="utf-8")
    if path.suffix.lower() in (".yaml", ".yml"):
        return yaml.safe_load(text)
    return json.loads(text)


def pointer_tokens(pointer: str) -> list[str]:
    if pointer in ("", "#"):
        return []
    if pointer.startswith("#"):
        pointer = pointer[1:]
    pointer = unquote(pointer)
    if not pointer.startswith("/"):
        raise ValueError(f"not a JSON pointer: {pointer!r}")
    return [t.replace("~1", "/").replace("~0", "~") for t in pointer[1:].split("/")]


def resolve_pointer(document: Any, pointer: str) -> Any:
    node = document
    for token in pointer_tokens(pointer):
        if isinstance(node, list):
            if not re.fullmatch(r"0|[1-9][0-9]*", token) or int(token) >= len(node):
                raise KeyError(f"array index {token!r} out of range")
            node = node[int(token)]
        elif isinstance(node, dict):
            if token not in node:
                raise KeyError(f"member {token!r} not found")
            node = node[token]
        else:
            raise KeyError(f"cannot descend into a scalar at {token!r}")
    return node


# --------------------------------------------------------------------------------------------------
# RFC 6902 JSON Patch
# --------------------------------------------------------------------------------------------------

class PatchError(Exception):
    pass


def _parent_and_key(document: Any, pointer: str) -> tuple[Any, str]:
    tokens = pointer_tokens(pointer)
    if not tokens:
        raise PatchError("operation on the document root is not supported here")
    parent = document
    for token in tokens[:-1]:
        try:
            parent = parent[int(token)] if isinstance(parent, list) else parent[token]
        except (KeyError, IndexError, ValueError) as exc:
            raise PatchError(f"path {pointer!r} does not exist ({exc})") from exc
    return parent, tokens[-1]


def _array_index(parent: list, key: str, allow_end: bool) -> int:
    if key == "-" and allow_end:
        return len(parent)
    if not re.fullmatch(r"0|[1-9][0-9]*", key):
        raise PatchError(f"invalid array index {key!r}")
    index = int(key)
    limit = len(parent) if allow_end else len(parent) - 1
    if index > limit:
        raise PatchError(f"array index {index} out of range")
    return index


def _get(document: Any, pointer: str) -> Any:
    try:
        return resolve_pointer(document, pointer)
    except KeyError as exc:
        raise PatchError(f"path {pointer!r} does not exist ({exc})") from exc


def _add(document: Any, pointer: str, value: Any) -> Any:
    if not pointer_tokens(pointer):
        return copy.deepcopy(value)
    parent, key = _parent_and_key(document, pointer)
    if isinstance(parent, list):
        parent.insert(_array_index(parent, key, allow_end=True), copy.deepcopy(value))
    elif isinstance(parent, dict):
        parent[key] = copy.deepcopy(value)
    else:
        raise PatchError(f"cannot add below a scalar at {pointer!r}")
    return document


def _remove(document: Any, pointer: str) -> Any:
    parent, key = _parent_and_key(document, pointer)
    if isinstance(parent, list):
        del parent[_array_index(parent, key, allow_end=False)]
    elif isinstance(parent, dict):
        if key not in parent:
            raise PatchError(f"path {pointer!r} does not exist")
        del parent[key]
    else:
        raise PatchError(f"cannot remove below a scalar at {pointer!r}")
    return document


def _replace(document: Any, pointer: str, value: Any) -> Any:
    if not pointer_tokens(pointer):
        return copy.deepcopy(value)
    parent, key = _parent_and_key(document, pointer)
    if isinstance(parent, list):
        parent[_array_index(parent, key, allow_end=False)] = copy.deepcopy(value)
    elif isinstance(parent, dict):
        if key not in parent:
            raise PatchError(f"path {pointer!r} does not exist")
        parent[key] = copy.deepcopy(value)
    else:
        raise PatchError(f"cannot replace below a scalar at {pointer!r}")
    return document


def apply_patch(document: Any, patch: list[dict]) -> Any:
    result = copy.deepcopy(document)
    for index, operation in enumerate(patch):
        op = operation.get("op")
        path = operation.get("path")
        try:
            if not isinstance(path, str):
                raise PatchError("missing 'path'")
            if op == "add":
                result = _add(result, path, operation["value"])
            elif op == "remove":
                result = _remove(result, path)
            elif op == "replace":
                result = _replace(result, path, operation["value"])
            elif op in ("move", "copy"):
                source = operation["from"]
                value = copy.deepcopy(_get(result, source))
                if op == "move":
                    result = _remove(result, source)
                result = _add(result, path, value)
            elif op == "test":
                if _get(result, path) != operation["value"]:
                    raise PatchError(f"test failed at {path!r}")
            else:
                raise PatchError(f"unknown op {op!r}")
        except KeyError as exc:
            raise PatchError(f"operation #{index} ({op} {path}): missing member {exc}") from exc
        except PatchError as exc:
            raise PatchError(f"operation #{index} ({op} {path}): {exc}") from exc
    return result


# --------------------------------------------------------------------------------------------------
# $ref resolution (local and external files)
# --------------------------------------------------------------------------------------------------

def iter_refs(node: Any, trail: str = "#"):
    """Yield (location, ref) for every '$ref' member whose value is a string."""
    if isinstance(node, dict):
        for key, value in node.items():
            here = f"{trail}/{str(key).replace('~', '~0').replace('/', '~1')}"
            if key == "$ref" and isinstance(value, str):
                yield here, value
            else:
                yield from iter_refs(value, here)
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from iter_refs(value, f"{trail}/{index}")


class RefResolver:
    """Checks that every $ref reachable from a root document resolves (following external files once)."""

    def __init__(self) -> None:
        self.cache: dict[Path, Any] = {}
        self.visited: set[Path] = set()
        self.problems: list[str] = []
        self.count = 0

    def load(self, path: Path) -> Any:
        resolved = path.resolve()
        if resolved not in self.cache:
            self.cache[resolved] = load_document(resolved)
        return self.cache[resolved]

    def check_file(self, path: Path) -> None:
        resolved = path.resolve()
        if resolved in self.visited:
            return
        self.visited.add(resolved)
        document = self.load(resolved)
        for location, ref in iter_refs(document):
            self.count += 1
            target, fragment = urldefrag(ref)
            where = f"{resolved.name} {location}"
            if re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", target):
                # Absolute URI ($id-based or remote). Only same-file anchors are resolvable offline.
                self.problems.append(f"{where}: absolute $ref {ref!r} is not resolvable offline")
                continue
            if target:
                external = (resolved.parent / unquote(target)).resolve()
                if not external.is_file():
                    self.problems.append(f"{where}: external file not found for $ref {ref!r}")
                    continue
                try:
                    external_doc = self.load(external)
                except Exception as exc:  # noqa: BLE001 - report any parse failure
                    self.problems.append(f"{where}: cannot parse {external.name}: {exc}")
                    continue
                self.check_file(external)
            else:
                external_doc = document
            if fragment:
                try:
                    resolve_pointer(external_doc, "#" + fragment if not fragment.startswith("#") else fragment)
                except (KeyError, ValueError) as exc:
                    self.problems.append(f"{where}: $ref {ref!r} does not resolve ({exc})")


# --------------------------------------------------------------------------------------------------
# Checks
# --------------------------------------------------------------------------------------------------

def check_schemas(report: Report, root: Path) -> dict[str, Any]:
    report.section("1. JSON Schemas (2020-12)")
    schemas: dict[str, Any] = {}
    for name in ("workflow-v0.schema.json", "connection-definition-v0.schema.json"):
        path = root / "contracts" / name
        if not path.is_file():
            report.fail(f"{name}: file not found")
            continue
        try:
            schema = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            report.fail(f"{name}: invalid JSON ({exc})")
            continue
        dialect = schema.get("$schema", "")
        report.check(
            dialect.rstrip("#") == "https://json-schema.org/draft/2020-12/schema",
            f"{name}: declares the 2020-12 dialect",
            f"{name}: $schema is {dialect!r}, expected https://json-schema.org/draft/2020-12/schema",
        )
        try:
            Draft202012Validator.check_schema(schema)
            report.ok(f"{name}: valid against the 2020-12 meta-schema")
        except SchemaError as exc:
            report.fail(f"{name}: meta-schema violation at {list(exc.absolute_path)}: {exc.message}")
            continue
        resolver = RefResolver()
        resolver.check_file(path)
        report.check(
            not resolver.problems,
            f"{name}: all {resolver.count} $refs resolve",
            f"{name}: " + "; ".join(resolver.problems),
        )
        schemas[name] = schema
    return schemas


def validation_errors(validator: Draft202012Validator, instance: Any, limit: int = 3) -> list[str]:
    errors = sorted(validator.iter_errors(instance), key=lambda e: [str(p) for p in e.absolute_path])
    return [f"/{'/'.join(str(p) for p in e.absolute_path)}: {e.message[:160]}" for e in errors[:limit]]


def check_examples(report: Report, root: Path, schemas: dict[str, Any]) -> None:
    report.section("2. Examples against schemas")
    workflow_schema = schemas.get("workflow-v0.schema.json")
    connection_schema = schemas.get("connection-definition-v0.schema.json")

    workflow_files = sorted((root / "examples").glob("*.json"))
    if workflow_schema is None:
        report.fail("workflow schema unavailable; skipping workflow examples")
    elif not (root / "examples" / "purchase-approval.json").is_file():
        report.fail("examples/purchase-approval.json not found")
    else:
        validator = Draft202012Validator(workflow_schema)
        for path in workflow_files:
            errors = validation_errors(validator, json.loads(path.read_text("utf-8")))
            report.check(not errors, f"examples/{path.name} is a valid workflow", path.name + ": " + "; ".join(errors))

    connection_files = sorted((root / "examples" / "connections").glob("*.json"))
    report.check(bool(connection_files), f"found {len(connection_files)} connection definition(s)", "no examples/connections/*.json found")
    if connection_schema is not None:
        validator = Draft202012Validator(connection_schema)
        for path in connection_files:
            errors = validation_errors(validator, json.loads(path.read_text("utf-8")))
            rel = path.relative_to(root).as_posix()
            report.check(not errors, f"{rel} is a valid connection definition", f"{rel}: " + "; ".join(errors))


def check_compiler_cases(report: Report, root: Path, schemas: dict[str, Any]) -> None:
    report.section("3. Compiler fixtures (RFC 6902 patches vs workflow schema)")
    path = root / "examples" / "fixtures" / "compiler-cases.json"
    workflow_schema = schemas.get("workflow-v0.schema.json")
    if workflow_schema is None or not path.is_file():
        report.fail("compiler-cases.json or the workflow schema is unavailable")
        return
    fixture = json.loads(path.read_text(encoding="utf-8"))
    base_path = root / fixture["base"]
    if not base_path.is_file():
        report.fail(f"base document {fixture['base']} not found")
        return
    base = json.loads(base_path.read_text(encoding="utf-8"))
    validator = Draft202012Validator(workflow_schema)
    connection_validator = (
        Draft202012Validator(schemas["connection-definition-v0.schema.json"])
        if "connection-definition-v0.schema.json" in schemas
        else None
    )

    cases = fixture["cases"]
    names = [c["name"] for c in cases]
    report.check(len(names) == len(set(names)), f"{len(cases)} cases with unique names", "duplicate case names in compiler-cases.json")

    applied = expected_invalid = expected_valid = 0
    for case in cases:
        name = case["name"]
        try:
            document = apply_patch(base, case.get("patch", []))
            applied += 1
        except PatchError as exc:
            report.fail(f"{name}: patch does not apply: {exc}")
            continue
        errors = validation_errors(validator, document)
        should_fail = bool(SCHEMA_FAILURE_CODES.intersection(case.get("expectBlockers", [])))
        if should_fail:
            expected_invalid += 1
            report.check(
                bool(errors),
                f"{name}: rejected by the schema as expected ({', '.join(sorted(SCHEMA_FAILURE_CODES.intersection(case['expectBlockers'])))})",
                f"{name}: expected a schema violation but the document is schema-valid",
            )
        else:
            expected_valid += 1
            report.check(
                not errors,
                f"{name}: schema-valid (compiler-layer blockers: {', '.join(case.get('expectBlockers') or []) or 'none'})",
                f"{name}: unexpected schema violation: " + "; ".join(errors),
            )

    # Connection definitions referenced by contexts must exist and be valid.
    contexts = [fixture.get("defaultContext", {})] + [c["context"] for c in cases if "context" in c]
    referenced = {conn["definition"] for ctx in contexts for conn in ctx.get("connections", []) if "definition" in conn}
    for rel in sorted(referenced):
        target = root / rel
        if not target.is_file():
            report.fail(f"context references missing connection definition {rel}")
        elif connection_validator is not None:
            errors = validation_errors(connection_validator, json.loads(target.read_text("utf-8")))
            report.check(not errors, f"context connection definition {rel} is valid", f"{rel}: " + "; ".join(errors))

    report.check(
        applied == len(cases),
        f"all {applied} patch sets apply ({expected_invalid} expected schema-invalid, {expected_valid} schema-valid)",
        f"only {applied} of {len(cases)} patch sets applied",
    )

    scenarios = root / "examples" / "fixtures" / "purchase-scenarios.json"
    if scenarios.is_file():
        try:
            json.loads(scenarios.read_text(encoding="utf-8"))
            report.ok("examples/fixtures/purchase-scenarios.json parses")
        except json.JSONDecodeError as exc:
            report.fail(f"purchase-scenarios.json: invalid JSON ({exc})")


def resolve_local_ref(document: Any, ref: str) -> Any | None:
    if not ref.startswith("#"):
        return None
    try:
        return resolve_pointer(document, ref)
    except (KeyError, ValueError):
        return None


def check_openapi(report: Report, root: Path) -> None:
    report.section("4. contracts/openapi.yaml")
    path = root / "contracts" / "openapi.yaml"
    if not path.is_file():
        report.fail("contracts/openapi.yaml not found")
        return
    try:
        document = load_document(path)
    except yaml.YAMLError as exc:
        report.fail(f"openapi.yaml: invalid YAML ({exc})")
        return

    report.check(
        str(document.get("openapi", "")).startswith("3.1"),
        f"openapi version {document.get('openapi')}",
        f"openapi version must be 3.1.x, found {document.get('openapi')!r}",
    )

    resolver = RefResolver()
    resolver.check_file(path)
    report.check(
        not resolver.problems,
        f"all {resolver.count} $refs resolve (internal and external files: {len(resolver.visited) - 1})",
        "unresolved $refs: " + "; ".join(resolver.problems[:10]) + (" ..." if len(resolver.problems) > 10 else ""),
    )

    operations: list[tuple[str, str, dict, list]] = []  # (method, path template, operation, path-level parameters)
    for template, item in (document.get("paths") or {}).items():
        if not template.startswith("/"):
            report.fail(f"path {template!r} must start with '/'")
        for method in HTTP_METHODS:
            if method in item:
                operations.append((method, template, item[method], item.get("parameters", [])))

    report.check(bool(operations), f"{len(operations)} operations found", "no operations found")

    # Unique operationIds
    seen: dict[str, str] = {}
    duplicates, missing = [], []
    for method, template, operation, _ in operations:
        operation_id = operation.get("operationId")
        label = f"{method.upper()} {template}"
        if not operation_id:
            missing.append(label)
        elif operation_id in seen:
            duplicates.append(f"{operation_id} ({seen[operation_id]} and {label})")
        else:
            seen[operation_id] = label
    report.check(not missing, "every operation has an operationId", "operations without operationId: " + ", ".join(missing))
    report.check(not duplicates, f"{len(seen)} operationIds are unique", "duplicate operationIds: " + ", ".join(duplicates))

    # Path parameters match templates
    problems = []
    for method, template, operation, path_level in operations:
        label = f"{method.upper()} {template}"
        declared: dict[str, dict] = {}
        for parameter in list(path_level) + list(operation.get("parameters", [])):
            if "$ref" in parameter:
                resolved = resolve_local_ref(document, parameter["$ref"])
                if resolved is None:
                    problems.append(f"{label}: parameter {parameter['$ref']} is not a local, resolvable reference")
                    continue
                parameter = resolved
            if parameter.get("in") == "path":
                declared[parameter.get("name", "")] = parameter
        in_template = set(re.findall(r"\{([^}/]+)\}", template))
        for name in sorted(in_template - set(declared)):
            problems.append(f"{label}: template parameter {{{name}}} is not declared")
        for name in sorted(set(declared) - in_template):
            problems.append(f"{label}: declared path parameter {name!r} is not in the template")
        for name, parameter in declared.items():
            if parameter.get("required") is not True:
                problems.append(f"{label}: path parameter {name!r} must be required: true")
    report.check(not problems, "path parameters match the path templates", "; ".join(problems[:10]))

    # Permission annotation on every operation
    no_permission = [
        f"{m.upper()} {t}" for m, t, op, _ in operations
        if not (isinstance(op.get("x-orqix-permission"), str) and op["x-orqix-permission"].strip())
    ]
    report.check(not no_permission, "every operation has x-orqix-permission", "missing x-orqix-permission: " + ", ".join(no_permission))

    # Audit annotation on mutating operations
    exempt_present = {op.get("operationId") for _, _, op, _ in operations} & AUDIT_EXEMPT_OPERATIONS
    stale = AUDIT_EXEMPT_OPERATIONS - exempt_present
    report.check(not stale, "audit exemptions all refer to existing operations", f"audit exemptions with no matching operation: {sorted(stale)}")
    no_audit = [
        f"{m.upper()} {t} ({op.get('operationId')})" for m, t, op, _ in operations
        if m in AUDIT_REQUIRED_METHODS
        and op.get("operationId") not in AUDIT_EXEMPT_OPERATIONS
        and not (isinstance(op.get("x-orqix-audit"), str) and op["x-orqix-audit"].strip())
    ]
    mutating = sum(1 for m, *_ in operations if m in AUDIT_REQUIRED_METHODS)
    report.check(
        not no_audit,
        f"all {mutating - len(exempt_present)} auditable POST/PUT/DELETE operations have x-orqix-audit ({len(exempt_present)} exempt)",
        "missing x-orqix-audit: " + ", ".join(no_audit),
    )


def main(argv: list[str] | None = None) -> int:
    default_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description="Orqix contract/schema/fixture/OpenAPI consistency check")
    parser.add_argument("--root", type=Path, default=default_root, help="repository root (default: %(default)s)")
    args = parser.parse_args(argv)
    root = args.root.resolve()

    print(f"Orqix contract check - repository root {root}")
    report = Report()
    schemas = check_schemas(report, root)
    check_examples(report, root, schemas)
    check_compiler_cases(report, root, schemas)
    check_openapi(report, root)

    print()
    if report.failures:
        print(f"RESULT: FAIL - {len(report.failures)} of {report.checks} checks failed")
        for failure in report.failures:
            print(f"  - {failure}")
        return 1
    print(f"RESULT: PASS - {report.checks} checks")
    return 0


if __name__ == "__main__":
    sys.exit(main())
