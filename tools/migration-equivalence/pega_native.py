"""Inventory and effective-rule resolution of a native Pega rule archive (MIG11, docs/build/S112).

This is an independent, offline implementation of the decoding and rule-resolution part of the
backend's native Pega adapter. Tests compare the backend's results with this one, so the two must
stay separate implementations of the same written rules (S112 "Rule resolution"). It reads the
clipboard XML shape of rule pages (<pagedata>, REPEATINGTYPE lists); it never executes source content
and it does not convert rules (conversion lives in the backend only).

The grammar is a strict synthetic subset. Fidelity against genuine Pega exports is AC01/EXT01
customer qualification and is not established by this tool.

    python tools/migration-equivalence/pega_native.py archive.zip [--out result.json]
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import sys
import xml.parsers.expat
import zipfile

DECODER = "pega-native-0.1"
MAX_ENTRIES, MAX_ENTRY, MAX_TOTAL = 200, 1_048_576, 4_194_304
MAX_ELEMENTS, MAX_DEPTH = 2_000, 64
AVAILABILITY = {"Yes", "No", "Blocked", "Withdrawn", "Final"}
VERSION = re.compile(r"^(\d{2})-(\d{2})-(\d{2})$")
NAME = re.compile(r"^[A-Za-z@][A-Za-z0-9_@.-]{0,127}$")
STAMP = re.compile(r"^ #\d{8}T\d{6}\.\d{3} GMT$")
DATE = re.compile(r"^\d{8}$")
DATETIME = re.compile(r"^\d{8}T\d{6}\.\d{3} GMT$")
UNRESOLVED_TYPES = {"Rule-Application", "Rule-Obj-Class"}


class Refused(ValueError):
    """The archive as a whole cannot be decoded safely."""


class Node:
    def __init__(self, name: str, attrs: dict[str, str]):
        self.name, self.attrs, self.children, self.text = name, attrs, [], ""


def parse_xml(data: bytes) -> Node:
    """Bounded parse that refuses DTDs and entities, like the backend's SafeXml."""
    root: list[Node] = []
    stack: list[Node] = []
    count = 0

    def start(name, attrs):
        nonlocal count
        count += 1
        if count > MAX_ELEMENTS or len(stack) >= MAX_DEPTH:
            raise ValueError("limits")
        node = Node(name, dict(attrs))
        (stack[-1].children if stack else root).append(node)
        stack.append(node)

    def end(_name):
        stack.pop()

    def chars(text):
        if stack:
            stack[-1].text += text

    def refuse(*_args):
        raise ValueError("doctype")

    parser = xml.parsers.expat.ParserCreate()
    parser.StartElementHandler, parser.EndElementHandler, parser.CharacterDataHandler = start, end, chars
    parser.StartDoctypeDeclHandler = refuse
    parser.EntityDeclHandler = refuse
    parser.Parse(data, True)
    if len(root) != 1:
        raise ValueError("root")
    return root[0]


def page_value(node: Node, path: str):
    """A property value: text, a PageList (list of dicts) or a PropertyList (list of str)."""
    kind = node.attrs.get("REPEATINGTYPE")
    if set(node.attrs) - {"REPEATINGTYPE"}:
        raise ValueError(f"{path}: unsupported attribute")
    if kind is None:
        if node.children:
            raise ValueError(f"{path}: embedded pages are not supported")
        return node.text.strip()
    if kind not in ("PageList", "PropertyList") or node.text.strip():
        raise ValueError(f"{path}: unsupported list")
    rows = []
    for index, row in enumerate(node.children, start=1):
        if row.name != "rowdata" or row.attrs != {"REPEATINGINDEX": str(index)}:
            raise ValueError(f"{path}: rows must be rowdata numbered from 1")
        if kind == "PropertyList":
            if row.children:
                raise ValueError(f"{path}: property list rows hold text")
            rows.append(row.text.strip())
        else:
            if row.text.strip():
                raise ValueError(f"{path}: page list rows hold properties")
            rows.append(properties(row, f"{path}/{index}"))
    return rows


def properties(node: Node, path: str) -> dict:
    result = {}
    for child in node.children:
        if ":" in child.name:
            raise ValueError(f"{path}/{child.name}: namespaced elements are not part of the page grammar")
        if child.name in result:
            raise ValueError(f"{path}/{child.name}: repeated property")
        result[child.name] = page_value(child, f"{path}/{child.name}")
    return result


def version_tuple(text: str) -> tuple[int, int, int]:
    match = VERSION.match(text)
    return tuple(int(part) for part in match.groups())  # type: ignore[return-value]


def identity(fields: dict) -> dict:
    obj = fields.get("pxObjClass")
    if not isinstance(obj, str) or not obj:
        raise ValueError("pxObjClass is required")
    if obj.startswith("Data-"):
        if not isinstance(fields.get("pzInsKey"), str) or not fields["pzInsKey"]:
            raise ValueError("pzInsKey is required")
        return {"status": "data", "ruleType": obj}
    if obj == "System-User-MyRules":
        # A rule delegation record: kept as provenance of the delegated rule, never converted.
        for key in ("pzInsKey", "pyRuleInsKey", "pyAccessGroup"):
            if not isinstance(fields.get(key), str) or not fields[key] or len(fields[key]) > 300:
                raise ValueError(f"{key} is required")
        return {"status": "delegation", "ruleType": obj}
    if not obj.startswith("Rule-"):
        raise ValueError("instance class")
    for key in ("pyClassName", "pyRuleName", "pyRuleSet", "pyRuleSetVersion", "pyRuleAvailable", "pzInsKey"):
        value = fields.get(key)
        if not isinstance(value, str) or not value or len(value) > 200 or "\n" in value:
            raise ValueError(f"{key} is required")
    if not NAME.match(fields["pyRuleName"]) or not NAME.match(fields["pyClassName"]) or not NAME.match(fields["pyRuleSet"]):
        raise ValueError("invalid name")
    if not VERSION.match(fields["pyRuleSetVersion"]) or fields["pyRuleAvailable"] not in AVAILABILITY:
        raise ValueError("invalid version or availability")
    prefix = f"{obj} {fields['pyClassName']} {fields['pyRuleName']}".upper()
    key = fields["pzInsKey"]
    if not key.upper().startswith(prefix) or (key[len(prefix):] and not STAMP.match(key[len(prefix):])):
        raise ValueError("pzInsKey does not match the rule identity")
    q = qualifier(fields)
    return {"status": "rule", "ruleType": obj, "className": fields["pyClassName"], "ruleName": fields["pyRuleName"],
            "ruleset": fields["pyRuleSet"], "version": fields["pyRuleSetVersion"],
            "availability": fields["pyRuleAvailable"], "circumstanced": q is not None, "qualifier": q}


def qualifier(fields: dict):
    """Property circumstance, date circumstance and date range (S115). Several on one rule are multivariate."""
    parts = []
    if "pyCircumstanceProp" in fields or "pyCircumstanceVal" in fields:
        if not (fields.get("pyCircumstanceProp") and fields.get("pyCircumstanceVal")):
            raise ValueError("circumstance needs a property and a value")
        parts.append({"kind": "property", "property": fields["pyCircumstanceProp"], "value": fields["pyCircumstanceVal"]})
    if "pyCircumstanceDateProp" in fields or "pyCircumstanceDate" in fields:
        date = fields.get("pyCircumstanceDate")
        if not fields.get("pyCircumstanceDateProp") or not isinstance(date, str) or not DATE.match(date):
            raise ValueError("a date circumstance needs a property and a YYYYMMDD date")
        parts.append({"kind": "date", "property": fields["pyCircumstanceDateProp"], "date": date})
    if "pyStartDateTime" in fields or "pyEndDateTime" in fields:
        start, end = fields.get("pyStartDateTime") or None, fields.get("pyEndDateTime") or None
        for value in (start, end):
            if value is not None and (not isinstance(value, str) or not DATETIME.match(value)):
                raise ValueError("a date range needs YYYYMMDDTHHMMSS.mmm GMT bounds")
        if start is None and end is None or start and end and start > end:
            raise ValueError("a date range needs at least one bound, in order")
        parts.append({"kind": "dateRange", "from": start, "to": end})
    if not parts:
        return None
    return parts[0] if len(parts) == 1 else {"kind": "multivariate", "parts": parts}


def qkey(rule: dict) -> str:
    return json.dumps(rule["qualifier"], sort_keys=True) if rule["qualifier"] else ""


def read_archive(data: bytes) -> list[dict]:
    if len(data) < 4 or data[:2] != b"PK":
        raise Refused("Expected a ZIP/RAP/JAR archive.")
    entries, names, total = [], set(), 0
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as zf:
            infos = zf.infolist()
            if len(infos) > MAX_ENTRIES:
                raise Refused("Archive exceeds 200 entries.")
            for info in infos:
                name = info.filename.replace("\\", "/")
                if len(name) > 500 or name.startswith("/") or ":" in name or ".." in name.split("/") or name in names:
                    raise Refused("Unsafe or duplicate archive path.")
                names.add(name)
                if name.endswith("/"):
                    continue
                content = zf.read(info)
                total += len(content)
                if len(content) > MAX_ENTRY or total > MAX_TOTAL:
                    raise Refused("Archive exceeds extraction limits.")
                entries.append({"path": name, "bytes": content})
    except zipfile.BadZipFile as error:
        raise Refused("The archive is corrupt or unsupported.") from error
    if not entries:
        raise Refused("The archive contains no files.")
    return entries


def decode(entry: dict) -> dict:
    path, content = entry["path"], entry["bytes"]
    result = {"path": path, "sha256": hashlib.sha256(content).hexdigest()}
    if path == "META-INF/MANIFEST.MF":
        return {**result, "status": "manifest"}
    if not path.lower().endswith(".xml"):
        return {**result, "status": "refused", "reason": "PEGA_NATIVE_ENTRY_UNDECODED"}
    try:
        root = parse_xml(content)
    except (ValueError, xml.parsers.expat.ExpatError):
        return {**result, "status": "refused", "reason": "PEGA_NATIVE_XML_INVALID"}
    if root.name != "pagedata" or root.attrs or root.text.strip():
        return {**result, "status": "refused", "reason": "PEGA_NATIVE_PAGE_ROOT"}
    try:
        fields = properties(root, "")
    except ValueError:
        return {**result, "status": "refused", "reason": "PEGA_NATIVE_PAGE_GRAMMAR"}
    try:
        ident = identity(fields)
    except ValueError:
        return {**result, "status": "refused", "reason": "PEGA_NATIVE_IDENTITY_INVALID"}
    return {**result, **ident, "fields": fields}


def rank_key(rule: dict, chain: list[str], stack: dict[str, tuple[int, tuple]]):
    return (chain.index(rule["className"]), stack[rule["ruleset"]][0], tuple(-p for p in version_tuple(rule["version"])))


def resolve(candidates: list[dict], chain: list[str], stack: dict[str, tuple[int, tuple]]) -> dict:
    outcome: dict[str, str] = {}
    live = []
    for c in candidates:
        if c["ruleset"] not in stack:
            outcome[c["path"]] = "rulesetNotInApplication"
            continue
        major, minor, patch = version_tuple(c["version"])
        smajor, sminor, spatch = stack[c["ruleset"]][1]
        if major != smajor or (minor, patch) > (sminor, spatch):
            outcome[c["path"]] = "versionNotInApplication"
        elif c["availability"] == "No":
            outcome[c["path"]] = "notAvailable"
        else:
            live.append(c)
    for w in [c for c in live if c["availability"] == "Withdrawn"]:
        outcome[w["path"]] = "withdrawn"
        for c in live:
            if (c is not w and c["className"] == w["className"] and c["ruleset"] == w["ruleset"] and qkey(c) == qkey(w)
                    and version_tuple(c["version"])[0] == version_tuple(w["version"])[0]
                    and version_tuple(c["version"]) < version_tuple(w["version"])):
                outcome[c["path"]] = "maskedByWithdrawn"
    live = sorted((c for c in live if c["path"] not in outcome), key=lambda c: (rank_key(c, chain, stack), c["path"]))
    result = {"status": "resolved", "chosen": None, "variants": []}
    base = [c for c in live if not c["qualifier"]]
    qualified = [c for c in live if c["qualifier"]]

    def rank(c):
        return rank_key(c, chain, stack)
    for c in qualified:
        outcome[c["path"]] = "circumstanced"  # unless it becomes a variant of a resolved base below
    if not base:
        result["status"] = "circumstanceUnresolved" if qualified else "noCandidate"
    elif len(base) > 1 and rank(base[0]) == rank(base[1]):
        result["status"] = "ambiguous"
        for c in base:
            outcome[c["path"]] = "duplicate" if rank(c) == rank(base[0]) else "lowerRanked"
    elif base[0]["availability"] == "Blocked":
        result["status"] = "blocked"
        outcome[base[0]["path"]] = "blocked"
        for c in base[1:]:
            outcome[c["path"]] = "belowBlocked"
    else:
        best = base[0]
        final = [c for c in base[1:] if c["availability"] == "Final" and c["ruleset"] != best["ruleset"]]
        if final:
            result["status"] = "finalOverridden"
            outcome[best["path"]] = "overridesFinal"
            for c in base[1:]:
                outcome[c["path"]] = "finalOverridden" if c in final else "lowerRanked"
        else:
            outcome[best["path"]] = "selected"
            for c in base[1:]:
                outcome[c["path"]] = "lowerRanked"
            unresolved, groups = False, {}
            for c in qualified:
                if chain.index(c["className"]) > chain.index(best["className"]):
                    outcome[c["path"]] = "lowerRanked"
                elif chain.index(c["className"]) < chain.index(best["className"]) or c["qualifier"]["kind"] == "multivariate":
                    unresolved = True
                else:
                    groups.setdefault(qkey(c), []).append(c)
            winners = []
            for _key, group in sorted(groups.items()):
                if len(group) > 1 and rank(group[0]) == rank(group[1]) or group[0]["availability"] == "Blocked":
                    unresolved = True
                    continue
                winners.append(group[0])
                outcome[group[0]["path"]] = "variant"
                for c in group[1:]:
                    outcome[c["path"]] = "lowerRanked"
            if len({(w["qualifier"]["kind"], w["qualifier"].get("property")) for w in winners}) > 1:
                unresolved = True
            if unresolved:
                result["status"] = "circumstanceUnresolved"
                outcome[best["path"]] = "baseFallback"
                for w in winners:
                    outcome[w["path"]] = "circumstanced"
            else:
                result["chosen"] = best["path"]
                result["variants"] = [{"rule": w["path"], **w["qualifier"]} for w in sorted(winners, key=lambda w: w["path"])]
    result["candidates"] = [{"rule": c["path"], "outcome": outcome[c["path"]]}
                            for c in sorted(candidates, key=lambda c: c["path"])]
    return result


def application(rules: list[dict], blockers: list[dict]) -> dict[str, tuple[int, tuple]] | None:
    apps = [r for r in rules if r["ruleType"] == "Rule-Application" and r["availability"] in ("Yes", "Final")]
    if not apps:
        blockers.append({"code": "PEGA_NATIVE_APPLICATION_MISSING", "paths": []})
        return None
    if len(apps) > 1:
        blockers.append({"code": "PEGA_NATIVE_APPLICATION_AMBIGUOUS", "paths": sorted(a["path"] for a in apps)})
        return None
    fields = apps[0]["fields"]
    allowed = {"pxObjClass", "pyClassName", "pyRuleName", "pyRuleSet", "pyRuleSetVersion", "pyRuleAvailable", "pzInsKey",
               "pyLabel", "pyDescription", "pyRuleSets", "pyBuiltOnApplications"}
    stack: dict[str, tuple[int, tuple]] = {}
    rows = fields.get("pyRuleSets")
    valid = not (set(fields) - allowed) and isinstance(rows, list) and 1 <= len(rows) <= 20
    for index, row in enumerate(rows if valid else []):
        name, version = row.get("pyRuleSetName"), row.get("pyRuleSetVersion")
        if (not isinstance(row, dict) or set(row) != {"pyRuleSetName", "pyRuleSetVersion"} or not isinstance(name, str)
                or not NAME.match(name) or name in stack or not isinstance(version, str) or not VERSION.match(version)):
            valid = False
            break
        stack[name] = (index, version_tuple(version))
    for row in fields.get("pyBuiltOnApplications", []) if valid else []:
        if not isinstance(row, dict) or row.get("pyApplicationName") != "PegaRULES" or set(row) - {"pyApplicationName", "pyApplicationVersion"}:
            valid = False
    if not valid:
        blockers.append({"code": "PEGA_NATIVE_APPLICATION_INVALID", "paths": [apps[0]["path"]]})
        return None
    return stack


def classes(rules: list[dict], stack, blockers: list[dict]) -> dict[str, dict]:
    defs: dict[str, list[dict]] = {}
    for r in rules:
        if r["ruleType"] != "Rule-Obj-Class" or r["ruleset"] not in stack or r["availability"] not in ("Yes", "Final"):
            continue
        major = version_tuple(r["version"])[0]
        smajor, sminor, spatch = stack[r["ruleset"]][1]
        if major != smajor or version_tuple(r["version"])[1:] > (sminor, spatch):
            continue
        defs.setdefault(r["className"], []).append(r)
    result = {}
    for name, found in sorted(defs.items()):
        found.sort(key=lambda r: (stack[r["ruleset"]][0], tuple(-p for p in version_tuple(r["version"]))))
        if len(found) > 1 and (stack[found[0]["ruleset"]][0], found[0]["version"]) == (stack[found[1]["ruleset"]][0], found[1]["version"]):
            blockers.append({"code": "PEGA_NATIVE_CLASS_AMBIGUOUS", "paths": sorted(r["path"] for r in found[:2])})
            continue
        f = found[0]["fields"]
        allowed = {"pxObjClass", "pyClassName", "pyRuleName", "pyRuleSet", "pyRuleSetVersion", "pyRuleAvailable", "pzInsKey",
                   "pyLabel", "pyDescription", "pyDerivesFrom", "pyClassType", "pyPatternInheritance"}
        if (set(f) - allowed or f["pyRuleName"] != name or not isinstance(f.get("pyDerivesFrom"), str)
                or not f["pyDerivesFrom"] or f.get("pyClassType") not in ("Abstract", "Concrete")
                or f.get("pyPatternInheritance", "true") not in ("true", "false")):
            blockers.append({"code": "PEGA_NATIVE_CLASS_INVALID", "paths": [found[0]["path"]]})
            continue
        result[name] = {"parent": f["pyDerivesFrom"], "pattern": f.get("pyPatternInheritance", "true") == "true"}
    return result


def chain_of(context: str, defs: dict[str, dict]) -> tuple[list[str], bool]:
    chain, visited, current = [], set(), context
    while True:
        if current in visited:
            return chain, False
        visited.add(current)
        if current not in chain:
            chain.append(current)
        definition = defs.get(current)
        if definition is None:
            break
        if definition["pattern"]:
            parts = current.split("-")
            for size in range(len(parts) - 1, 0, -1):
                prefix = "-".join(parts[:size])
                if prefix and prefix not in chain:
                    chain.append(prefix)
        if definition["parent"] == "@baseclass":
            break
        current = definition["parent"]
    if "@baseclass" not in chain:
        chain.append("@baseclass")
    return chain, True


def analyze(data: bytes) -> dict:
    entries = [decode(e) for e in read_archive(data)]
    blockers: list[dict] = []
    refused = sorted(e["path"] for e in entries if e["status"] == "refused")
    if refused:
        blockers.append({"code": "PEGA_NATIVE_ARCHIVE_INCOMPLETE", "paths": refused})
    rules = [e for e in entries if e["status"] == "rule"]
    keys: dict[str, list[str]] = {}
    for e in entries:
        if e["status"] in ("rule", "data", "delegation"):
            keys.setdefault(e["fields"]["pzInsKey"].upper(), []).append(e["path"])
    for paths in keys.values():
        if len(paths) > 1:
            blockers.append({"code": "PEGA_NATIVE_DUPLICATE_KEY", "paths": sorted(paths)})
    stack = application(rules, blockers)
    contexts, starts = {}, []
    if stack is not None:
        defs = classes(rules, stack, blockers)
        roots = sorted({r["className"] for r in rules if r["ruleType"] == "Rule-Obj-CaseType"
                        or (r["ruleType"] == "Rule-Obj-Flow" and r["fields"].get("pyIsStartingFlow") == "true")})
        for context in roots:
            chain, acyclic = chain_of(context, defs)
            if not acyclic:
                blockers.append({"code": "PEGA_NATIVE_INHERITANCE_CYCLE", "paths": [context]})
                continue
            grouped: dict[tuple[str, str], list[dict]] = {}
            for r in rules:
                if r["ruleType"] not in UNRESOLVED_TYPES and r["className"] in chain:
                    grouped.setdefault((r["ruleType"], r["ruleName"].upper()), []).append(r)
            resolved = {f"{t} {n}": resolve(c, chain, stack) for (t, n), c in sorted(grouped.items())}
            contexts[context] = {"chain": chain, "keys": resolved}
            # A starting point is an effective case type, or an effective flow marked as starting, of the context class.
            for result in resolved.values():
                chosen = next((r for r in rules if r["path"] == result["chosen"]), None)
                if chosen is None or chosen["className"] != context:
                    continue
                if chosen["ruleType"] == "Rule-Obj-CaseType":
                    starts.append({"rule": chosen["path"], "kind": "caseType", "context": context})
                elif chosen["ruleType"] == "Rule-Obj-Flow" and chosen["fields"].get("pyIsStartingFlow") == "true":
                    starts.append({"rule": chosen["path"], "kind": "flow", "context": context})
    return {"decoder": DECODER, "sourceSha256": hashlib.sha256(data).hexdigest(), "entries": entries,
            "archiveBlockers": blockers,
            "stack": None if stack is None else [[name, "%02d-%02d-%02d" % v] for name, (_i, v) in sorted(stack.items(), key=lambda s: s[1][0])],
            "contexts": contexts, "startingPoints": starts}


def expectation(analysis: dict) -> dict:
    """The comparable result: what the backend must report for the same archive."""
    keep = ("path", "sha256", "status", "reason", "ruleType", "className", "ruleName", "ruleset", "version",
            "availability", "circumstanced", "qualifier")
    return {**{k: v for k, v in analysis.items() if k != "entries"},
            "entries": [{k: e[k] for k in keep if k in e} for e in analysis["entries"]]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--out", type=Path, help="write the JSON result to a new file instead of standard output")
    args = parser.parse_args()
    try:
        with args.archive.open("rb") as handle:
            data = handle.read(1_048_577)
        if len(data) > 1_048_576:
            raise Refused("Archive exceeds 1 MiB.")
        text = json.dumps(expectation(analyze(data)), indent=2, sort_keys=True) + "\n"
        if args.out:
            with args.out.open("x", encoding="utf-8") as handle:
                handle.write(text)
        else:
            sys.stdout.write(text)
    except (Refused, OSError) as error:
        parser.exit(2, f"Refused: {error}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
