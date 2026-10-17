#!/usr/bin/env python3
"""Generate explicitly synthetic, deterministic Pega-like inventory/mapping fixtures.

No Pega export format is implemented. The XML describes chosen target behavior;
the canonical definitions are reviewed mapping templates, never inferred output.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[2]
VERSION = "synthetic-pega-like-1"
SOURCES = {
    "small": "examples/simple-review.json",
    "medium": "examples/medium-bounded-rework.json",
    "complex": "examples/complex-batch-review.json",
}
NOTICE = "SYNTHETIC TEST FIXTURE: NOT a native Pega export and NOT a customer export."


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def element(parent: ET.Element, name: str, text: object) -> ET.Element:
    result = ET.SubElement(parent, name)
    result.text = str(text)
    return result


def rule(scenario: str, name: str, rule_class: str) -> ET.Element:
    root = ET.Element("SyntheticPegaLikeRule", {"fixtureOnly": "true"})
    element(root, "ProvenanceNotice", NOTICE)
    element(root, "pxObjClass", rule_class)
    element(root, "pyClassName", "Orqix-Synthetic-Work-Review")
    element(root, "pyRuleName", f"Synthetic_{scenario}_{name}")
    element(root, "pyRuleSet", "OrqixSyntheticFixtures")
    element(root, "pyRuleSetVersion", "01-01-01")
    element(root, "SourceValidation", "syntheticFixture")
    element(root, "SourceVersion", VERSION)
    return root


def xml_bytes(root: ET.Element) -> bytes:
    ET.indent(root, space="  ")
    return ET.tostring(root, encoding="utf-8", xml_declaration=True) + b"\n"


def node_rule(scenario: str, node: dict, definition: dict) -> bytes:
    root = rule(scenario, node["id"], "Rule-Obj-Flow")
    element(root, "CanonicalTargetNodeId", node["id"])
    element(root, "DescriptiveStepType", node["type"])
    element(root, "Description", node["label"])
    # Descriptive JSON is XML text, not Pega executable code or an inferred translation.
    element(root, "ReviewedCanonicalConfiguration", json.dumps(node.get("config", {}), sort_keys=True))
    transitions = ET.SubElement(root, "DescriptiveOutgoingRoutes")
    for transition in definition["transitions"]:
        if transition["from"] == node["id"]:
            element(transitions, "Route", json.dumps(transition, sort_keys=True))
    config = node.get("config", {})
    if node["type"] == "approval":
        element(root, "pySLAName", "SyntheticDeadlineMetadata")
        element(root, "DescriptiveAssignmentGroup", config["candidateGroupRef"])
    if node["type"] == "startWorkflow":
        element(root, "pySubFlowName", config["workflowRef"])
        if "repeat" in config:
            ET.SubElement(root, "DescriptiveBoundedRepeat", {
                "maxIterations": str(config["repeat"]["maxIterations"]),
                "untilOutcome": config["repeat"]["untilOutcome"],
            })
        if "forEach" in config:
            ET.SubElement(root, "DescriptiveForEach", {
                key: str(value) for key, value in config["forEach"].items()
            })
    if node["type"] == "wait":
        element(root, "DescriptiveDurableTimer", config["duration"])
    return xml_bytes(root)


def archive_bytes(entries: dict[str, bytes]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, content in sorted(entries.items()):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            entry.compress_type = zipfile.ZIP_STORED
            archive.writestr(entry, content)
    return output.getvalue()


def package(scenario: str, source: str) -> tuple[dict[str, bytes], dict]:
    original = (REPO / source).read_bytes()
    definition = json.loads(original)
    definition_bytes = json_bytes(definition)
    entries: dict[str, bytes] = {}
    mappings: dict[str, dict] = {}
    for node in definition["nodes"]:
        filename = f"rules/flows/{node['id']}.xml"
        entries[filename] = node_rule(scenario, node, definition)
        mappings[filename] = {
            "disposition": "mapped",
            "targetNodeIds": [node["id"]],
            "explanation": (
                f"Synthetic descriptor manually maps to the reviewed {node['type']} target "
                f"{node['id']}. Target behavior is authored in definition.json, not "
                "converted from a native Pega rule. Source-owner equivalence is unverified."
            ),
        }
    exclusions = [
        ("rules/properties/request-data.xml", "Rule-Obj-Property", "RequestData", {
            "DescriptiveFields": json.dumps(definition["inputSchema"], sort_keys=True),
        }, "Property inventory is descriptive only. Target form types, requiredness and bounds are explicitly authored in definition.json; native Pega property semantics are not imported."),
        ("rules/routing/manager-queue.xml", "Rule-Declare-DecisionTree", "ManagerQueue", {
            "DescriptiveQueue": "SyntheticManagerQueue", "pyWhenName": "SyntheticRoutingEligibility",
        }, "Pega-like work-queue, skill and identity resolution is excluded. Target approval groups use the explicitly configured local managers group; this is not source routing equivalence."),
        ("rules/sla/descriptive-deadline.xml", "Rule-Obj-ServiceLevel", "DeadlineMetadata", {
            "DescriptiveGoal": "PT4H", "DescriptiveDeadline": "P1D", "DescriptiveCalendar": "SyntheticBusinessCalendar",
        }, "Source SLA, escalation and business-calendar metadata is excluded. Only explicitly authored target timers and approval settings execute; no native SLA translation is claimed."),
        ("rules/activities/unsupported-legacy-step.xml", "Rule-Obj-Activity", "LegacySideEffect", {
            "pyActivityName": "SyntheticLegacySideEffect", "DescriptiveUnsupportedOperation": "LegacyJavaAndDatabaseSideEffect",
        }, "Unsupported source activity code and legacy database effects are deliberately excluded and never executed. No equivalent external side effect is included in these target scenarios."),
    ]
    for filename, rule_class, name, values, explanation in exclusions:
        root = rule(scenario, name, rule_class)
        for key, value in values.items():
            element(root, key, value)
        entries[filename] = xml_bytes(root)
        mappings[filename] = {"disposition": "excluded", "targetNodeIds": [], "explanation": explanation}
    manifest = {
        "format": "orqix-pega-synthetic-archive-0.1",
        "scenario": scenario,
        "sourceValidation": "syntheticFixture",
        "sourceVersion": VERSION,
        "nativeExport": False,
        "customerExport": False,
        "notice": NOTICE,
        "provenance": {"generator": "examples/migration/pega-synthetic/generate.py", "canonicalTemplate": source, "canonicalTemplateSha256": sha256(original)},
        "reviewedDefinitionSha256": sha256(definition_bytes),
        "entrySha256": {name: sha256(value) for name, value in sorted(entries.items())},
    }
    entries["manifest.json"] = json_bytes(manifest)
    mappings["manifest.json"] = {
        "disposition": "excluded", "targetNodeIds": [],
        "explanation": "Synthetic provenance/checksum metadata has no executable target node. It explicitly identifies the archive as neither a native export nor a customer export.",
    }
    zipped = archive_bytes(entries)
    outputs = {f"{scenario}/archive.zip": zipped, f"{scenario}/definition.json": definition_bytes,
               f"{scenario}/mappings.json": json_bytes(dict(sorted(mappings.items())))}
    suite_entry = {"id": scenario, "archive": f"{scenario}/archive.zip", "definition": f"{scenario}/definition.json",
                   "fileMappings": f"{scenario}/mappings.json", "dependencies": [] if scenario == "small" else ["small"],
                   "sourceCanonicalDefinition": source, "archiveSha256": sha256(zipped),
                   "definitionSha256": sha256(definition_bytes), "fileMappingsSha256": sha256(outputs[f"{scenario}/mappings.json"])}
    return outputs, suite_entry


def generated() -> dict[str, bytes]:
    output: dict[str, bytes] = {}
    workflows = []
    for scenario, source in SOURCES.items():
        artifacts, manifest = package(scenario, source)
        output.update(artifacts)
        workflows.append(manifest)
    output["suite.json"] = json_bytes({"format": "orqix-pega-synthetic-suite-0.1", "sourceValidation": "syntheticFixture",
        "sourceVersion": VERSION, "notice": NOTICE, "workflows": workflows})
    return output


def validate(outputs: dict[str, bytes]) -> None:
    suite = json.loads(outputs["suite.json"])
    assert [item["id"] for item in suite["workflows"]] == ["small", "medium", "complex"]
    for workflow in suite["workflows"]:
        definition = json.loads(outputs[workflow["definition"]])
        mappings = json.loads(outputs[workflow["fileMappings"]])
        targets = {node["id"] for node in definition["nodes"]}
        covered = set()
        with zipfile.ZipFile(io.BytesIO(outputs[workflow["archive"]])) as archive:
            assert set(archive.namelist()) == set(mappings), "Every source file needs exactly one disposition"
            for name in archive.namelist():
                mapping = mappings[name]
                assert mapping["explanation"].strip()
                assert mapping["disposition"] in {"mapped", "excluded"}
                if mapping["disposition"] == "mapped":
                    assert mapping["targetNodeIds"] and set(mapping["targetNodeIds"]) <= targets
                    covered.update(mapping["targetNodeIds"])
                else:
                    assert mapping["targetNodeIds"] == []
                if name.endswith(".xml"):
                    assert ET.fromstring(archive.read(name)).attrib["fixtureOnly"] == "true"
            manifest = json.loads(archive.read("manifest.json"))
            assert manifest["nativeExport"] is False and manifest["customerExport"] is False
            assert manifest["sourceValidation"] == "syntheticFixture"
            for name, digest in manifest["entrySha256"].items():
                assert sha256(archive.read(name)) == digest
        assert covered == targets, "Every target node needs explicit descriptive source provenance"
        for path_key, digest_key in [("archive", "archiveSha256"), ("definition", "definitionSha256"), ("fileMappings", "fileMappingsSha256")]:
            assert sha256(outputs[workflow[path_key]]) == workflow[digest_key]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Validate fixtures and ensure committed bytes match generation without writing")
    args = parser.parse_args()
    outputs = generated()
    validate(outputs)
    assert generated() == outputs, "Generator must reproduce every byte in the same process"
    if args.check:
        stale = [name for name, data in outputs.items() if not (ROOT / name).is_file() or (ROOT / name).read_bytes() != data]
        if stale:
            print("Missing or stale generated files: " + ", ".join(stale), file=sys.stderr)
            return 1
    else:
        for name, data in outputs.items():
            target = ROOT / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    print(f"Validated {len(outputs)} deterministic synthetic fixture files; all archive entries and target nodes have explicit dispositions.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
