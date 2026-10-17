"""Convert a bounded Pega decision-table authoring JSON snapshot to importable DMN.

This is not a RAP decoder or a Pega rule resolver. See docs/build/S55-pega-decisions.md.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

NS = "http://www.omg.org/spec/DMN/20151101/dmn.xsd"
ADAPTER = "pega-decision-snapshot-0.1"
TOP = {"pxObjClass", "pyClassName", "pyPurpose", "pyRuleName", "pyLabel",
       "pyDescription", "pyRuleSet", "pyRuleSetVersion", "pyRuleAvailable",
       "pyDefaultAction", "pyDefaultResult", "pyEvaluateAllRows", "pyColumns", "pyResults"}
COLUMN = {"pxObjClass", "pyProperty", "pyPropertyLabel", "pyColumnDataType",
          "pyCondition", "pyDefaultOperator"}


class Unsupported(ValueError):
    pass


def require(condition, path, reason):
    if not condition:
        raise Unsupported(f"{path}: {reason}")


def fields(value, allowed, path):
    require(isinstance(value, dict), path, "expected an object")
    unknown = sorted(set(value) - allowed)
    require(not unknown, path, f"unsupported fields: {', '.join(unknown)}")


def literal(value, path):
    # Deliberately narrow: no expressions, quoting, operators, escapes or wildcard ambiguity.
    require(isinstance(value, str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9 _-]{0,99}", value),
            path, "expected a plain text literal (letters, digits, spaces, underscores, hyphens)")
    return json.dumps(value)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, key, "duplicate JSON key")
        result[key] = value
    return result


def convert(raw):
    require(len(raw) <= 1_048_576, "$", "snapshot exceeds 1 MiB")
    try:
        source = json.loads(raw, object_pairs_hook=unique_object)
    except (UnicodeError, json.JSONDecodeError, RecursionError) as error:
        raise Unsupported("$: invalid JSON snapshot") from error
    fields(source, TOP, "$")
    require(source.get("pxObjClass") == "Rule-Declare-DecisionTable", "pxObjClass", "only decision tables supported")
    for key in ("pyClassName", "pyPurpose", "pyLabel", "pyRuleSet", "pyRuleSetVersion"):
        require(isinstance(source.get(key), str) and 0 < len(source[key]) <= 200 and source[key].isprintable(),
                key, "explicit rule identity required")
    require(re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,79}", source["pyPurpose"]), "pyPurpose", "unsupported identifier")
    reserved = {"pegaDefinitions", "pegaTable", "pegaInput", "pegaExpression", "pegaOutput"}
    reserved.update(f"{prefix}{i}" for prefix in ("pegaRow", "input", "output") for i in range(102))
    require(source["pyPurpose"] not in reserved, "pyPurpose", "identifier collides with generated DMN element")
    require(re.fullmatch(r"\d{2}-\d{2}-\d{2}", source["pyRuleSetVersion"]), "pyRuleSetVersion", "expected NN-NN-NN")
    require(source.get("pyRuleName", source["pyPurpose"]) == source["pyPurpose"], "pyRuleName", "must match purpose")
    require(source.get("pyRuleAvailable") in ("Yes", "Final"), "pyRuleAvailable", "explicit available rule required")
    require(source.get("pyEvaluateAllRows") == "no", "pyEvaluateAllRows", "explicit first-match mode required")
    require(source.get("pyDefaultAction") == "RETURN", "pyDefaultAction", "explicit RETURN required; SET unsupported")
    columns = source.get("pyColumns")
    require(isinstance(columns, list) and len(columns) == 1, "pyColumns", "exactly one input column supported")
    column = columns[0]
    fields(column, COLUMN, "pyColumns[0]")
    require(column.get("pxObjClass", "Embed-DecisionTable-Column") == "Embed-DecisionTable-Column",
            "pyColumns[0].pxObjClass", "unexpected column class")
    prop = column.get("pyProperty")
    require(isinstance(prop, str) and re.fullmatch(r"\.[A-Za-z][A-Za-z0-9_]{0,79}", prop),
            "pyColumns[0].pyProperty", "only a direct case property supported")
    kind = column.get("pyColumnDataType")
    require(kind in ("text", "integer", "decimal"), "pyColumns[0].pyColumnDataType", "unsupported type")
    operator = column.get("pyDefaultOperator", "=")
    require(operator in ("=", "!=", ">", ">=", "<", "<="), "pyDefaultOperator", "unsupported operator")
    results = source.get("pyResults")
    conditions = column.get("pyCondition")
    require(isinstance(results, list) and 1 <= len(results) <= 100, "pyResults", "expected 1 to 100 rows")
    require(isinstance(conditions, list) and len(conditions) == len(results), "pyCondition", "row lengths differ")
    outputs = [literal(value, f"pyResults[{i}]") for i, value in enumerate(results)]
    outputs.append(literal(source.get("pyDefaultResult"), "pyDefaultResult"))
    tests = []
    for i, condition in enumerate(conditions):
        path = f"pyColumns[0].pyCondition[{i}]"
        require(isinstance(condition, str) and len(condition) <= 100, path, "expected bounded condition string")
        if kind == "text":
            require(operator == "=", path, "text supports equality only")
            tests.append(literal(condition, path))
        else:
            match = re.fullmatch(r"(>=|<=|!=|=|>|<)?(-?(?:0|[1-9][0-9]{0,14})(?:\.[0-9]{1,8})?)", condition)
            require(match is not None, path, "only literal numeric comparisons supported")
            op, number = match.groups()
            require(kind != "integer" or "." not in number, path, "integer column requires integer operands")
            op = op or operator
            tests.append(f"not({number})" if op == "!=" else number if op == "=" else f"{op} {number}")
    tests.append("-")
    ET.register_namespace("", NS)
    def child(parent, tag, **attrs):
        return ET.SubElement(parent, f"{{{NS}}}{tag}", attrs)
    decision_id = source["pyPurpose"]
    root = ET.Element(f"{{{NS}}}definitions", {"id": "pegaDefinitions", "name": source["pyLabel"], "namespace": "urn:orqix:pega-snapshot"})
    decision = child(root, "decision", id=decision_id, name=source["pyLabel"])
    table = child(decision, "decisionTable", id="pegaTable", hitPolicy="FIRST")
    inp = child(table, "input", id="pegaInput", label=prop[1:])
    expr = child(inp, "inputExpression", id="pegaExpression", typeRef={"text": "string", "integer": "integer", "decimal": "double"}[kind])
    child(expr, "text").text = prop[1:]
    child(table, "output", id="pegaOutput", name="result", typeRef="string")
    mappings = []
    for i, (test, output) in enumerate(zip(tests, outputs)):
        row_id = f"pegaRow{i + 1}"
        row = child(table, "rule", id=row_id)
        child(child(row, "inputEntry", id=f"input{i}"), "text").text = test
        child(child(row, "outputEntry", id=f"output{i}"), "text").text = output
        mappings.append({"targetRuleId": row_id, "sourcePath": f"/pyResults/{i}" if i < len(results) else "/pyDefaultResult"})
    xml = ET.tostring(root, encoding="utf-8", xml_declaration=True)
    report = {"adapter": ADAPTER, "sourceSha256": hashlib.sha256(raw).hexdigest(),
              "sourceFormat": "pega-decision-authoring-json-subset",
              "targetSha256": hashlib.sha256(xml).hexdigest(), "decisionId": decision_id,
              "sourceIdentity": {key: source[key] for key in ("pyClassName", "pyPurpose", "pyRuleSet", "pyRuleSetVersion")},
              "rowMappings": mappings, "nativeRapDecoded": False, "sourceRuntimeEquivalenceEstablished": False,
              "presentationExcluded": [key for key in ("pyDescription",) if key in source]
                  + ["pyColumns[0].pyPropertyLabel"] * ("pyPropertyLabel" in column),
              "limitations": ["Caller must select the effective source rule; inheritance and circumstances are not resolved.",
                              "Target import review and publication are required. Source execution has not been observed."]}
    return xml, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path, help="New directory for decision.dmn and provenance.json")
    args = parser.parse_args()
    try:
        with args.source.open("rb") as handle:
            raw = handle.read(1_048_577)
        xml, report = convert(raw)
        args.output.mkdir(parents=True, exist_ok=False)
        (args.output / "decision.dmn").write_bytes(xml)
        (args.output / "provenance.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    except (Unsupported, OSError) as error:
        parser.exit(2, f"Conversion refused: {error}\n")


if __name__ == "__main__":
    main()
