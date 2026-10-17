import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

from pega_decision import convert, Unsupported, NS

FIXTURE = Path(__file__).resolve().parents[2] / "examples/migration/pega-decision/synthetic.json"


class PegaDecisionTest(unittest.TestCase):
    def setUp(self):
        self.source = json.loads(FIXTURE.read_text())

    def run_conversion(self, source):
        return convert(json.dumps(source).encode())

    def test_provenance_and_order(self):
        raw = FIXTURE.read_bytes()
        xml, report = convert(raw)
        root = ET.fromstring(xml)
        self.assertEqual([e.text for e in root.findall(f".//{{{NS}}}inputEntry/{{{NS}}}text")], [">= 10000", ">= 5000", "-"])
        self.assertEqual(report["sourceSha256"], hashlib.sha256(raw).hexdigest())
        self.assertEqual(report["targetSha256"], hashlib.sha256(xml).hexdigest())
        self.assertFalse(report["nativeRapDecoded"])
        self.assertFalse(report["sourceRuntimeEquivalenceEstablished"])
        self.assertEqual(report["rowMappings"][-1]["sourcePath"], "/pyDefaultResult")

    def test_checked_in_dmn_is_exact_converter_output(self):
        xml, _ = convert(FIXTURE.read_bytes())
        self.assertEqual(xml, (FIXTURE.parent / "generated/decision.dmn").read_bytes())

    def test_all_comparison_operators(self):
        for op, expected in [("=", "5000"), ("!=", "not(5000)"), ("<", "< 5000"), ("<=", "<= 5000"), (">", "> 5000"), (">=", ">= 5000")]:
            with self.subTest(op=op):
                self.source["pyColumns"][0]["pyCondition"] = [op + "5000", "10000"]
                xml, _ = self.run_conversion(self.source)
                self.assertEqual(ET.fromstring(xml).find(f".//{{{NS}}}inputEntry/{{{NS}}}text").text, expected)

    def test_text_equality(self):
        self.source["pyColumns"][0].update(pyColumnDataType="text", pyCondition=["Approved", "Pending"])
        xml, _ = self.run_conversion(self.source)
        self.assertIn(b'"Approved"', xml)

    def test_unknown_behavior_is_rejected_even_if_empty(self):
        for field, value in [("pyInitialPropSet", []), ("pyPropertyColumns", []), ("pyCircumstanceProp", "Region"), ("pyRedirectRule", "true"), ("pyDelegatedRestrictions", {})]:
            with self.subTest(field=field):
                source = dict(self.source, **{field: value})
                with self.assertRaisesRegex(Unsupported, field):
                    self.run_conversion(source)

    def test_invalid_semantics(self):
        for field, value in [("pyEvaluateAllRows", "yes"), ("pyDefaultAction", "SET"), ("pyRuleAvailable", "Blocked"), ("pyRuleName", "Other"), ("pyPurpose", "pegaTable"), ("pyResults", []), ("pyDefaultResult", "@Call()")]:
            with self.subTest(field=field), self.assertRaises(Unsupported):
                self.run_conversion(dict(self.source, **{field: value}))

    def test_required_execution_context_is_not_inferred(self):
        for field in ("pyEvaluateAllRows", "pyDefaultAction", "pyRuleAvailable", "pyRuleSet", "pyRuleSetVersion"):
            source = dict(self.source)
            del source[field]
            with self.subTest(field=field), self.assertRaises(Unsupported):
                self.run_conversion(source)

    def test_unsupported_columns(self):
        for field, value in [("pyProperty", "Param.Amount"), ("pyProperty", ".Nested.Amount"), ("pyCondition", ["@Call()", "5000"]), ("pyCondition", ["5000"]), ("pyColumnDataType", "date"), ("pyOrConditions", []), ("pyUseRange", "true")]:
            source = copy.deepcopy(self.source)
            source["pyColumns"][0][field] = value
            with self.subTest(field=field), self.assertRaises(Unsupported):
                self.run_conversion(source)
        self.source["pyColumns"].append(copy.deepcopy(self.source["pyColumns"][0]))
        with self.assertRaises(Unsupported):
            self.run_conversion(self.source)

    def test_bad_json_and_resource_limit(self):
        for raw in [b'{"pyPurpose":"one","pyPurpose":"two"}', b'PK\x03\x04binary', b' ' * 1_048_577, b'[]']:
            with self.assertRaises(Unsupported):
                convert(raw)

    def test_cli_creates_import_and_evidence_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "converted"
            command = [sys.executable, str(Path(__file__).with_name("pega_decision.py")), str(FIXTURE), str(output)]
            self.assertEqual(subprocess.run(command, capture_output=True).returncode, 0)
            before = (output / "decision.dmn").read_bytes()
            self.assertTrue((output / "provenance.json").exists())
            self.assertEqual(subprocess.run(command, capture_output=True).returncode, 2)
            self.assertEqual((output / "decision.dmn").read_bytes(), before)

    def test_cli_rejection_creates_no_output(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "bad.json"
            source.write_text('{"pxObjClass":"Rule-Obj-Activity"}')
            output = Path(directory) / "converted"
            result = subprocess.run([sys.executable, str(Path(__file__).with_name("pega_decision.py")), str(source), str(output)], capture_output=True)
            self.assertEqual(result.returncode, 2)
            self.assertIn(b"pxObjClass", result.stderr)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
