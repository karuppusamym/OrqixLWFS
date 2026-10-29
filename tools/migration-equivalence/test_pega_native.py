import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pega_native  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "examples" / "migration" / "pega-native"
sys.path.insert(0, str(FIXTURES))
import generate  # noqa: E402


def archive(entries):
    return generate.archive(entries)


def app(rulesets=(("Purchasing", "01-01-10"),)):
    return generate.page("Rule-Application", "@baseclass", "App", "Purchasing", "01-01-01", body={
        "pyRuleSets": generate.PageList([{"pyRuleSetName": n, "pyRuleSetVersion": v} for n, v in rulesets])})


def klass(name, parent, pattern="true"):
    return generate.page("Rule-Obj-Class", name, name, "Purchasing", "01-01-01",
                         body={"pyDerivesFrom": parent, "pyClassType": "Concrete", "pyPatternInheritance": pattern})


def flow(cls, name, ruleset="Purchasing", version="01-01-01", available="Yes", stamp="20261001T090000.000", **extra):
    return generate.page("Rule-Obj-Flow", cls, name, ruleset, version, available, stamp=stamp,
                         body={"pyIsStartingFlow": "true", **extra})


class FixtureTest(unittest.TestCase):
    def test_committed_fixtures_and_expectations_are_reproducible(self):
        for name, data in generate.generated().items():
            self.assertEqual((FIXTURES / name).read_bytes(), data, name)

    def test_purchase_request_resolution(self):
        result = pega_native.analyze((FIXTURES / "purchase-request.zip").read_bytes())
        self.assertEqual(result["archiveBlockers"], [])
        self.assertEqual(result["stack"], [["PurchasingOrg", "01-02-01"], ["Purchasing", "01-01-10"]])
        keys = result["contexts"]["ACME-Purchasing-Work-Request"]["keys"]
        outcomes = lambda key: {c["rule"]: c["outcome"] for c in keys[key]["candidates"]}
        self.assertEqual(keys["Rule-Obj-Flow APPROVEREQUEST"]["chosen"], "flows/ApproveRequest.xml")
        self.assertEqual(outcomes("Rule-Obj-Flow APPROVEREQUEST")["flows/ApproveRequest-sandbox.xml"], "rulesetNotInApplication")
        self.assertEqual(keys["Rule-Declare-DecisionTable APPROVALLEVEL"]["chosen"], "decisions/ApprovalLevel-01-01-01.xml")
        self.assertEqual(outcomes("Rule-Declare-DecisionTable APPROVALLEVEL")["decisions/ApprovalLevel-01-01-05.xml"], "notAvailable")
        self.assertEqual(keys["Rule-HTML-Section REQUESTDETAILS"]["chosen"], "sections/RequestDetails-Work.xml")
        self.assertEqual(outcomes("Rule-HTML-Section REQUESTDETAILS")["sections/RequestDetails-Request-01-01-02.xml"], "maskedByWithdrawn")
        self.assertEqual(keys["Rule-Obj-When NEEDSVENDORCHECK"]["chosen"], "when/NeedsVendorCheck-Request.xml")
        self.assertEqual(result["startingPoints"], [{"rule": "casetypes/PurchaseRequest.xml", "kind": "caseType",
                                                     "context": "ACME-Purchasing-Work-Request"}])
        self.assertEqual({e["status"] for e in result["entries"]}, {"manifest", "rule", "data"})

    def test_refusals_fixture_reports_blocked_circumstanced_and_undecoded(self):
        result = pega_native.analyze((FIXTURES / "refusals.zip").read_bytes())
        self.assertEqual(result["archiveBlockers"], [{"code": "PEGA_NATIVE_ARCHIVE_INCOMPLETE", "paths": ["binary/legacy.bin"]}])
        keys = result["contexts"]["ACME-Purchasing-Work"]["keys"]
        self.assertEqual(keys["Rule-Declare-DecisionTable TIER"]["status"], "blocked")
        self.assertEqual(keys["Rule-Obj-FlowAction APPROVE"]["status"], "circumstanceUnresolved")
        self.assertIsNone(keys["Rule-Obj-FlowAction APPROVE"]["chosen"])


class ResolutionTest(unittest.TestCase):
    def analyze(self, entries):
        return pega_native.analyze(archive({"app.xml": app(), **entries}))

    def test_closer_class_beats_higher_ruleset_and_version(self):
        result = pega_native.analyze(archive({
            "app.xml": app((("Org", "01-01-01"), ("Purchasing", "01-01-10"))),
            "c1.xml": klass("A-B", "A"), "c2.xml": klass("A", "@baseclass"),
            "f1.xml": flow("A-B", "Go", version="01-01-01"),
            "f2.xml": flow("A", "Go", ruleset="Org", version="01-01-01", stamp="20261001T090100.000"),
        }))
        context = result["contexts"]["A-B"]
        self.assertEqual(context["chain"], ["A-B", "A", "@baseclass"])
        self.assertEqual(context["keys"]["Rule-Obj-Flow GO"]["chosen"], "f1.xml")

    def test_higher_ruleset_beats_higher_version_in_same_class(self):
        result = pega_native.analyze(archive({
            "app.xml": app((("Org", "01-01-01"), ("Purchasing", "01-01-10"))), "c.xml": klass("A", "@baseclass"),
            "f1.xml": flow("A", "Go", version="01-01-09"),
            "f2.xml": flow("A", "Go", ruleset="Org", version="01-01-01", stamp="20261001T090100.000")}))
        self.assertEqual(result["contexts"]["A"]["keys"]["Rule-Obj-Flow GO"]["chosen"], "f2.xml")

    def test_version_above_stack_or_other_major_is_not_visible(self):
        result = self.analyze({"c.xml": klass("A", "@baseclass"),
                               "f1.xml": flow("A", "Go", version="01-02-01"),
                               "f2.xml": flow("A", "Go", version="02-01-01", stamp="20261001T090100.000"),
                               "f3.xml": flow("A", "Go", version="01-01-03", stamp="20261001T090200.000")})
        key = result["contexts"]["A"]["keys"]["Rule-Obj-Flow GO"]
        self.assertEqual(key["chosen"], "f3.xml")
        self.assertEqual({c["rule"]: c["outcome"] for c in key["candidates"]},
                         {"f1.xml": "versionNotInApplication", "f2.xml": "versionNotInApplication", "f3.xml": "selected"})

    def test_withdrawn_masks_lower_versions_only_in_its_own_class_and_ruleset(self):
        result = self.analyze({"c1.xml": klass("A-B", "A"), "c2.xml": klass("A", "@baseclass"),
                               "f1.xml": flow("A-B", "Go", version="01-01-01"),
                               "f2.xml": flow("A-B", "Go", version="01-01-05", available="Withdrawn", stamp="20261001T090100.000"),
                               "f3.xml": flow("A", "Go", version="01-01-02", stamp="20261001T090200.000")})
        key = result["contexts"]["A-B"]["keys"]["Rule-Obj-Flow GO"]
        self.assertEqual(key["chosen"], "f3.xml")

    def test_blocked_stops_resolution_and_final_cannot_be_overridden(self):
        blocked = self.analyze({"c.xml": klass("A", "@baseclass"), "f1.xml": flow("A", "Go", version="01-01-01"),
                                "f2.xml": flow("A", "Go", version="01-01-02", available="Blocked", stamp="20261001T090100.000")})
        self.assertEqual(blocked["contexts"]["A"]["keys"]["Rule-Obj-Flow GO"]["status"], "blocked")
        final = pega_native.analyze(archive({
            "app.xml": app((("Org", "01-01-01"), ("Purchasing", "01-01-10"))), "c.xml": klass("A", "@baseclass"),
            "f1.xml": flow("A", "Go", available="Final"),
            "f2.xml": flow("A", "Go", ruleset="Org", stamp="20261001T090100.000")}))
        self.assertEqual(final["contexts"]["A"]["keys"]["Rule-Obj-Flow GO"]["status"], "finalOverridden")

    def test_same_rank_is_ambiguous_and_circumstance_is_never_guessed(self):
        result = self.analyze({"c.xml": klass("A", "@baseclass"), "f1.xml": flow("A", "Go"),
                               "f2.xml": flow("A", "Go", stamp="20261001T090100.000")})
        self.assertEqual(result["contexts"]["A"]["keys"]["Rule-Obj-Flow GO"]["status"], "ambiguous")
        circumstanced = self.analyze({"c.xml": klass("A", "@baseclass"), "f1.xml": flow("A", "Go"),
                                      "f2.xml": generate.page("Rule-Obj-Flow", "A", "Go", "Purchasing", "01-01-01",
                                                              stamp="20261001T090100.000", circumstance=(".Tier", "Gold"))})
        self.assertEqual(circumstanced["contexts"]["A"]["keys"]["Rule-Obj-Flow GO"]["status"], "circumstanceUnresolved")

    def test_pattern_inheritance_can_be_switched_off_and_cycles_are_refused(self):
        result = self.analyze({"c1.xml": klass("A-B", "X", pattern="false"), "c2.xml": klass("X", "@baseclass"),
                               "f.xml": flow("A-B", "Go")})
        self.assertEqual(result["contexts"]["A-B"]["chain"], ["A-B", "X", "@baseclass"])
        cycle = self.analyze({"c1.xml": klass("P", "Q", pattern="false"), "c2.xml": klass("Q", "P", pattern="false"),
                              "f.xml": flow("P", "Go")})
        self.assertIn({"code": "PEGA_NATIVE_INHERITANCE_CYCLE", "paths": ["P"]}, cycle["archiveBlockers"])


class DecodingTest(unittest.TestCase):
    def entry(self, content):
        return next(e for e in pega_native.analyze(archive({"app.xml": app(), "x.xml": content}))["entries"] if e["path"] == "x.xml")

    def test_grammar_and_identity_refusals(self):
        cases = {
            b"<other/>": "PEGA_NATIVE_PAGE_ROOT",
            b"<pagedata><pxObjClass>Rule-Obj-Flow</pxObjClass><pxObjClass>x</pxObjClass></pagedata>": "PEGA_NATIVE_PAGE_GRAMMAR",
            b"<pagedata><pyX><pyY>1</pyY></pyX></pagedata>": "PEGA_NATIVE_PAGE_GRAMMAR",
            b'<pagedata><pyX REPEATINGTYPE="PageList"><rowdata REPEATINGINDEX="2"/></pyX></pagedata>': "PEGA_NATIVE_PAGE_GRAMMAR",
            b'<pagedata><pyX REPEATINGTYPE="PageGroup"/></pagedata>': "PEGA_NATIVE_PAGE_GRAMMAR",
            b"<pagedata><pxObjClass>Rule-Obj-Flow</pxObjClass></pagedata>": "PEGA_NATIVE_IDENTITY_INVALID",
            b"<pagedata><pxObjClass>System-Queue</pxObjClass></pagedata>": "PEGA_NATIVE_IDENTITY_INVALID",
            b'<!DOCTYPE x [<!ENTITY e "boom">]><pagedata>&e;</pagedata>': "PEGA_NATIVE_XML_INVALID",
            b"<pagedata>": "PEGA_NATIVE_XML_INVALID",
        }
        for content, reason in cases.items():
            with self.subTest(content=content):
                self.assertEqual(self.entry(content).get("reason"), reason)
        wrong_key = generate.page("Rule-Obj-Flow", "A", "Go", "Purchasing", "01-01-01").replace(b"RULE-OBJ-FLOW A GO", b"RULE-OBJ-FLOW A OTHER")
        self.assertEqual(self.entry(wrong_key)["reason"], "PEGA_NATIVE_IDENTITY_INVALID")

    def test_archive_level_refusals(self):
        with self.assertRaises(pega_native.Refused):
            pega_native.analyze(b"not a zip")
        unsafe = io.BytesIO()
        with zipfile.ZipFile(unsafe, "w") as zf:
            zf.writestr("../escape.xml", b"<pagedata/>")
        with self.assertRaises(pega_native.Refused):
            pega_native.analyze(unsafe.getvalue())
        missing = pega_native.analyze(archive({"f.xml": flow("A", "Go")}))
        self.assertEqual(missing["archiveBlockers"], [{"code": "PEGA_NATIVE_APPLICATION_MISSING", "paths": []}])
        built_on = pega_native.analyze(archive({"app.xml": generate.page("Rule-Application", "@baseclass", "App", "Purchasing", "01-01-01", body={
            "pyRuleSets": generate.PageList([{"pyRuleSetName": "Purchasing", "pyRuleSetVersion": "01-01-01"}]),
            "pyBuiltOnApplications": generate.PageList([{"pyApplicationName": "CustomerService"}])})}))
        self.assertEqual(built_on["archiveBlockers"][0]["code"], "PEGA_NATIVE_APPLICATION_INVALID")
        duplicate = pega_native.analyze(archive({"app.xml": app(), "a.xml": flow("A", "Go"), "b.xml": flow("A", "Go")}))
        self.assertIn({"code": "PEGA_NATIVE_DUPLICATE_KEY", "paths": ["a.xml", "b.xml"]}, duplicate["archiveBlockers"])

    def test_cli_writes_new_file_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "result.json"
            script = Path(pega_native.__file__)
            command = [sys.executable, "-I", str(script), str(FIXTURES / "purchase-request.zip"), "--out", str(out)]
            self.assertEqual(subprocess.run(command, capture_output=True).returncode, 0)
            self.assertEqual(json.loads(out.read_text(encoding="utf-8")),
                             json.loads((FIXTURES / "expected" / "purchase-request.json").read_text(encoding="utf-8")))
            self.assertEqual(subprocess.run(command, capture_output=True).returncode, 2)


if __name__ == "__main__":
    unittest.main()
