"""Consistency gates for the Orqix contract check (CI stage 1, section 5).

These checks tie the OpenAPI contract to the code on both sides, so that drift fails the build with a message that
names the exact thing to fix:

  5.1 security matrix        every operation is covered by the generated matrix (SecurityMatrixTest) or exempt
  5.2 permission vocabulary  every x-orqix-permission token is a Permission enum value or a known qualifier
  5.3 problem codes          backend ProblemCode == contract Problem.code enum == frontend problemMessages.ts
  5.4 audit actions          every action written in backend code is whitelisted in AuditWriter, and every
                             x-orqix-audit in the contract is whitelisted too
  5.5 generated client       frontend/src/api/schema.d.ts matches contracts/openapi.yaml
  5.6 line endings           no CRLF in contracts/, docs/, frontend/src, backend/*/src, tools, tests/e2e, .github

Every exemption below is explicit and carries its reason; adding one is a reviewed decision.
"""
from __future__ import annotations

import re
import shutil
import subprocess
from pathlib import Path
from typing import Any

# --- explicit exemptions --------------------------------------------------------------------------

# The security matrix (SecurityMatrixTest) calls every operation as foreign principals: /workspaces/* must be 404,
# /admin/* must be 403. Operations outside both families must be listed here with the reason.
MATRIX_EXEMPT_OPERATIONS = {
    "getMe": "reachable by every signed-in user by design; returns only the caller's own profile",
}

# Qualifiers in x-orqix-permission that are not Permission enum values: relationship or role checks.
PERMISSION_QUALIFIERS = {
    "authenticated", "platform.admin", "workspace.member", "initiator", "taskParticipant", "taskEligible",
    "currentAssignee", "assignee", "candidate", "recipient", "conversation.author", "requester",
    "inboundSignature",  # IN02: HMAC over the body with a per-trigger secret; no session or token
    "callbackToken",  # EX10 (S98): possession of a one-wait callback token; no session
    "extensionFileToken",  # UI02 (S116): a short-lived signed file path minted by the frame endpoint; no session
}
# Free-text fragments allowed inside an x-orqix-permission value.
PERMISSION_PHRASES = ("underlying action permission", "underlying read permission")

# Server problem codes that are deliberately not in the public contract enum.
PROBLEM_CODES_NOT_IN_CONTRACT = {"INTERNAL_ERROR": "unexpected defect (HTTP 500); the contract has no code for it"}

AUDIT_ACTION_SHAPE = re.compile(r"^[a-z]+(?:\.[a-z][a-z0-9-]*)+$")
EOL_DIRECTORIES = ("contracts", "docs", "frontend/src", "backend/*/src", "tools", "tests/e2e/specs", "tests/e2e/support", ".github")
EOL_SKIP_SUFFIXES = {".png", ".jpg", ".jpeg", ".gif", ".ico", ".zip", ".jar", ".pdf", ".woff", ".woff2", ".pyc", ".xlsx", ".docx", ".bat", ".cmd"}
SKIP_DIRS = {"node_modules", "target", "dist", "dist-mock", "__pycache__"}


# --- helpers --------------------------------------------------------------------------------------

def _operations(document: dict[str, Any]) -> list[tuple[str, str, dict]]:
    found = []
    for template, item in (document.get("paths") or {}).items():
        for method in ("get", "put", "post", "delete", "patch", "head", "options"):
            if isinstance(item.get(method), dict):
                found.append((method, template, item[method]))
    return found


def _split_call_args(text: str, start: int) -> list[str] | None:
    """`start` is the index after an opening parenthesis; returns the top-level arguments or None."""
    depth, args, current, i, quote = 0, [], [], start, None
    while i < len(text):
        ch = text[i]
        if quote:
            current.append(ch)
            if ch == "\\":
                i += 1
                if i < len(text):
                    current.append(text[i])
            elif ch == quote:
                quote = None
        elif ch == '"':
            quote = ch
            current.append(ch)
        elif ch in "([{":
            depth += 1
            current.append(ch)
        elif ch in ")]}":
            if depth == 0:
                args.append("".join(current).strip())
                return args
            depth -= 1
            current.append(ch)
        elif ch == "," and depth == 0:
            args.append("".join(current).strip())
            current = []
        else:
            current.append(ch)
        i += 1
    return None


def audit_whitelist(root: Path) -> tuple[set[str], str | None]:
    """Actions whitelisted in AuditWriter.METADATA_WHITELIST (the documented set); second value is an error."""
    path = root / "backend/app/src/main/java/com/orqix/audit/AuditWriter.java"
    if not path.is_file():
        return set(), f"{path.relative_to(root)} not found"
    text = path.read_text(encoding="utf-8")
    block = re.search(r"METADATA_WHITELIST\s*=\s*Map\.ofEntries\((.*?)\);\s*\n\s*private final", text, re.S)
    if not block:
        return set(), "AuditWriter.METADATA_WHITELIST not found (renamed? update tools/contract-check/consistency_gates.py)"
    return set(re.findall(r'Map\.entry\(\s*"([^"]+)"', block.group(1))), None


def audit_actions_in_code(root: Path) -> dict[str, list[str]]:
    """Audit-action string literals passed to AuditWriter.Entry / auditEntry / audit(...) in backend main code."""
    found: dict[str, list[str]] = {}
    call = re.compile(r"(?:new\s+AuditWriter\.Entry|\bauditEntry|\baudit)\s*\(")
    for source in sorted((root / "backend").glob("*/src/main/java/**/*.java")):
        if source.name == "AuditWriter.java":
            continue
        text = source.read_text(encoding="utf-8")
        for match in call.finditer(text):
            args = _split_call_args(text, match.end())
            if args is None:
                continue
            for arg in args:
                literal = re.fullmatch(r'"([^"]+)"', arg)
                if literal and AUDIT_ACTION_SHAPE.match(literal.group(1)):
                    found.setdefault(literal.group(1), []).append(str(source.relative_to(root)).replace("\\", "/"))
    return found


def problem_codes_backend(root: Path) -> tuple[set[str], str | None]:
    path = root / "backend/app/src/main/java/com/orqix/shared/problem/ProblemCode.java"
    if not path.is_file():
        return set(), "ProblemCode.java not found"
    text = path.read_text(encoding="utf-8")
    body = re.search(r"public enum ProblemCode\s*\{(.*?);\s*\n\s*private final", text, re.S)
    if not body:
        return set(), "ProblemCode enum body not found"
    cleaned = re.sub(r"/\*.*?\*/", "", body.group(1), flags=re.S)
    return set(re.findall(r"^\s*([A-Z][A-Z0-9_]+)\(\d{3},", cleaned, re.M)), None


def problem_codes_frontend(root: Path) -> tuple[set[str], str | None]:
    path = root / "frontend/src/lib/problemMessages.ts"
    if not path.is_file():
        return set(), "frontend/src/lib/problemMessages.ts not found"
    text = path.read_text(encoding="utf-8")
    body = re.search(r"const TABLE\s*=\s*\{(.*?)\n\}\s*satisfies", text, re.S)
    if not body:
        return set(), "'const TABLE = {...} satisfies Record<ProblemCode, Entry>' not found in problemMessages.ts"
    return set(re.findall(r"^\s{2}([A-Z][A-Z0-9_]+):", body.group(1), re.M)), None


# --- the gates ------------------------------------------------------------------------------------

def check_consistency(report: Any, root: Path, document: dict[str, Any] | None, skip_node: bool = False) -> None:
    report.section("5. Consistency gates (contract <-> code)")
    if not document:
        report.fail("contracts/openapi.yaml could not be loaded; consistency gates skipped")
        return
    operations = _operations(document)
    _check_security_matrix(report, root, operations)
    _check_permission_vocabulary(report, root, operations)
    _check_problem_codes(report, root, document)
    _check_audit_actions(report, root, operations)
    _check_generated_client(report, root, skip_node)
    _check_line_endings(report, root)


def _check_security_matrix(report: Any, root: Path, operations: list[tuple[str, str, dict]]) -> None:
    matrix_test = root / "backend/app/src/test/java/com/orqix/security/SecurityMatrixTest.java"
    report.check(matrix_test.is_file(), "security matrix test SecurityMatrixTest exists",
                 "backend/app/src/test/java/com/orqix/security/SecurityMatrixTest.java is missing: operations are no longer covered by the generated security matrix")
    uncovered = []
    exempt_used = set()
    for method, template, operation in operations:
        operation_id = operation.get("operationId", "?")
        if template.startswith("/workspaces/") or template.startswith("/admin/"):
            continue
        if operation_id in MATRIX_EXEMPT_OPERATIONS:
            exempt_used.add(operation_id)
            continue
        uncovered.append(f"{method.upper()} {template} ({operation_id})")
    report.check(
        not uncovered,
        f"every operation is in the security matrix (/workspaces/* 404, /admin/* 403) or explicitly exempt ({len(exempt_used)} exempt)",
        "operations outside /workspaces/ and /admin/ need a security-matrix entry or an entry in MATRIX_EXEMPT_OPERATIONS "
        "(tools/contract-check/consistency_gates.py) with a reason: " + ", ".join(uncovered),
    )
    stale = sorted(set(MATRIX_EXEMPT_OPERATIONS) - exempt_used)
    report.check(not stale, "security-matrix exemptions all refer to existing operations",
                 f"stale MATRIX_EXEMPT_OPERATIONS entries (no such operation): {stale}")


def _check_permission_vocabulary(report: Any, root: Path, operations: list[tuple[str, str, dict]]) -> None:
    permission_file = root / "backend/app/src/main/java/com/orqix/identity/Permission.java"
    if not permission_file.is_file():
        report.fail("identity/Permission.java not found")
        return
    enum_values = set(re.findall(r'[A-Z_]+\("([a-z][a-z.\-]+)"\)', permission_file.read_text(encoding="utf-8")))
    known = enum_values | PERMISSION_QUALIFIERS
    unknown: list[str] = []
    for _, _, operation in operations:
        value = str(operation.get("x-orqix-permission", ""))
        for phrase in PERMISSION_PHRASES:
            value = value.replace(phrase, " ")
        for token in re.split(r"[|&+\s]+", value):
            if token and token not in known:
                unknown.append(f"{operation.get('operationId')}: {token!r}")
    report.check(
        not unknown,
        f"every x-orqix-permission token is a Permission value ({len(enum_values)}) or a known qualifier",
        "unknown x-orqix-permission token(s): " + ", ".join(unknown)
        + " - add the permission to identity/Permission.java or the qualifier to PERMISSION_QUALIFIERS in tools/contract-check/consistency_gates.py",
    )
    unused = sorted(v for v in enum_values if not any(v in str(op.get("x-orqix-permission", "")) for _, _, op in operations))
    # Informational only: a permission can legitimately be used by the runtime and not by an HTTP operation.
    print(f"  INFO  permissions not named by any operation: {unused or 'none'}")


def _check_problem_codes(report: Any, root: Path, document: dict[str, Any]) -> None:
    problem = (((document.get("components") or {}).get("schemas") or {}).get("Problem") or {})
    contract = set(problem.get("properties", {}).get("code", {}).get("enum", []))
    report.check(bool(contract), f"contract Problem.code enum lists {len(contract)} codes",
                 "components.schemas.Problem.properties.code.enum not found in openapi.yaml")
    backend, error = problem_codes_backend(root)
    if error:
        report.fail(error)
    else:
        missing = sorted(backend - contract - set(PROBLEM_CODES_NOT_IN_CONTRACT))
        extra = sorted(contract - backend)
        report.check(not missing, f"every backend ProblemCode is in the contract enum ({len(backend)} codes, {len(PROBLEM_CODES_NOT_IN_CONTRACT)} exempt)",
                     f"ProblemCode value(s) missing from openapi.yaml Problem.code enum: {missing} - add them to the enum, then run `npm run api:generate` in frontend/")
        report.check(not extra, "every contract problem code exists in the backend ProblemCode enum",
                     f"openapi.yaml Problem.code lists code(s) the backend never produces: {extra}")
    frontend, error = problem_codes_frontend(root)
    if error:
        report.fail(error)
    else:
        missing = sorted(contract - frontend)
        extra = sorted(frontend - contract)
        report.check(not missing, f"every contract problem code has a message in frontend problemMessages.ts ({len(frontend)} entries)",
                     f"problem code(s) with no entry in frontend/src/lib/problemMessages.ts TABLE: {missing}")
        report.check(not extra, "problemMessages.ts has no entry for a code outside the contract",
                     f"problemMessages.ts TABLE entries not in the contract enum: {extra}")


def _check_audit_actions(report: Any, root: Path, operations: list[tuple[str, str, dict]]) -> None:
    whitelist, error = audit_whitelist(root)
    if error:
        report.fail(error)
        return
    report.ok(f"AuditWriter whitelists {len(whitelist)} audit actions")
    in_code = audit_actions_in_code(root)
    report.check(len(in_code) > 0, f"{len(in_code)} audit actions found in backend code",
                 "no audit actions found in backend code: the call-site scanner no longer matches the code")
    undocumented = {a: files for a, files in in_code.items() if a not in whitelist}
    report.check(
        not undocumented,
        "every audit action used in backend code is whitelisted in AuditWriter.METADATA_WHITELIST",
        "audit action(s) written in code but missing from AuditWriter.METADATA_WHITELIST (AuditWriter throws 'Unknown audit action' at runtime): "
        + "; ".join(f"{a} in {sorted(set(f))[0]}" for a, f in sorted(undocumented.items())),
    )
    contract_actions = {str(op["x-orqix-audit"]): op.get("operationId") for _, _, op in operations if op.get("x-orqix-audit")}
    missing = {a: o for a, o in contract_actions.items() if a not in whitelist}
    report.check(
        not missing,
        f"every x-orqix-audit action in the contract exists in the AuditWriter whitelist ({len(contract_actions)} actions)",
        "x-orqix-audit action(s) not whitelisted in AuditWriter: " + ", ".join(f"{a} ({o})" for a, o in sorted(missing.items())),
    )
    # Informational: whitelisted but never written by a literal call site (dead entry, or a dynamic call).
    unused = sorted(whitelist - set(in_code))
    print(f"  INFO  whitelisted actions without a literal call site: {unused or 'none'}")


def _check_generated_client(report: Any, root: Path, skip_node: bool) -> None:
    script = root / "frontend/scripts/api-check.mjs"
    node = shutil.which("node")
    if skip_node:
        print("  SKIP  schema.d.ts drift check (--skip-node)")
        return
    if not script.is_file() or not node or not (root / "frontend/node_modules/openapi-typescript").is_dir():
        print("  SKIP  schema.d.ts drift check: needs node and `npm ci` in frontend/ (CI runs it; locally run `npm run api:check` in frontend/)")
        return
    result = subprocess.run([node, str(script)], cwd=root / "frontend", capture_output=True, text=True, timeout=180)
    report.check(
        result.returncode == 0,
        "frontend/src/api/schema.d.ts is up to date with contracts/openapi.yaml",
        "frontend/src/api/schema.d.ts is out of date: run `npm run api:generate` in frontend/ and commit the result. " + (result.stderr or result.stdout).strip()[:300],
    )


def _eol_candidates(root: Path) -> list[Path]:
    """Tracked and not-ignored untracked text files in the guarded directories."""
    git = shutil.which("git")
    files: list[Path] = []
    if git and (root / ".git").exists():
        out = subprocess.run([git, "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", *EOL_DIRECTORIES],
                             cwd=root, capture_output=True, text=True)
        if out.returncode == 0:
            files = [root / name for name in out.stdout.split("\0") if name]
    if not files:
        for pattern in EOL_DIRECTORIES:
            for base in root.glob(pattern):
                files.extend(p for p in base.rglob("*") if p.is_file() and not SKIP_DIRS & set(p.parts))
    return [p for p in files if p.is_file() and p.suffix.lower() not in EOL_SKIP_SUFFIXES]


def files_with_crlf(root: Path) -> list[Path]:
    bad = []
    for path in _eol_candidates(root):
        try:
            data = path.read_bytes()
        except OSError:
            continue
        if b"\0" in data[:4096]:
            continue  # binary
        if b"\r\n" in data:
            bad.append(path)
    return bad


def fix_line_endings(root: Path) -> list[Path]:
    fixed = []
    for path in files_with_crlf(root):
        path.write_bytes(path.read_bytes().replace(b"\r\n", b"\n"))
        fixed.append(path)
    return fixed


def _check_line_endings(report: Any, root: Path) -> None:
    bad = files_with_crlf(root)
    shown = ", ".join(str(p.relative_to(root)).replace("\\", "/") for p in bad[:15]) + (f" ... (+{len(bad) - 15} more)" if len(bad) > 15 else "")
    report.check(
        not bad,
        "no CRLF line endings in contracts/, docs/, frontend/src, backend/*/src, tools, tests/e2e, .github",
        f"{len(bad)} file(s) contain CRLF (the repository is LF-only, .gitattributes eol=lf): {shown}. "
        "Fix with `python tools/contract-check/contract_check.py --fix-eol` or configure your editor for LF.",
    )
